/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.sam.markduplicates;

import com.google.common.collect.Ordering;
import picard.PicardException;
import picard.cmdline.CommandLineProgramProperties;
import picard.cmdline.Option;
import picard.cmdline.programgroups.SamOrBam;
import picard.sam.DuplicationMetrics;
import htsjdk.samtools.ReservedTagConstants;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.*;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.samtools.util.SortingLongCollection;
import picard.sam.markduplicates.util.*;
import htsjdk.samtools.DuplicateScoringStrategy.ScoringStrategy;
import picard.sam.util.ReadNameInterface;

import java.io.*;
import java.util.*;

/**
 * A better duplication marking algorithm that handles all cases including clipped
 * and gapped alignments.
 *
 * @author Tim Fennell
 */
@CommandLineProgramProperties(
        usage = MarkDuplicates.USAGE_SUMMARY + MarkDuplicates.USAGE_DETAILS,
        usageShort = MarkDuplicates.USAGE_SUMMARY,
        programGroup = SamOrBam.class
)
public class MarkDuplicates extends AbstractMarkDuplicatesCommandLineProgram {
    static final String USAGE_SUMMARY = "Identifies duplicate reads.  ";
    static final String USAGE_DETAILS =
            "This tool locates and tags duplicate reads (both PCR and optical/sequencing-driven) in a BAM or SAM file, where\n" +
                    "duplicate reads are defined as originating from the same original fragment of DNA. Duplicates are identified as read\n" +
                    "pairs having identical 5' positions (coordinate and strand) for both reads in a mate pair (and optionally, matching\n" +
                    "unique molecular identifier reads; see BARCODE_TAG option). Optical, or more broadly Sequencing, duplicates are\n" +
                    "duplicates that appear clustered together spatially during sequencing and can arise from optical/image-processing\n" +
                    "artifacts or from bio-chemical processes during clonal amplification and sequencing; they are identified using the\n" +
                    "READ_NAME_REGEX and the OPTICAL_DUPLICATE_PIXEL_DISTANCE options.\n" +
                    "\n" +
                    "The tool's main output is a new SAM or BAM file in which duplicates have been identified in the SAM flags field, or\n" +
                    "optionally removed (see REMOVE_DUPLICATE and REMOVE_SEQUENCING_DUPLICATES), and optionally marked with a duplicate type\n" +
                    "in the 'DT' optional attribute. In addition, it also outputs a metrics file containing the numbers of\n" +
                    "READ_PAIRS_EXAMINED, UNMAPPED_READS, UNPAIRED_READS, UNPAIRED_READ DUPLICATES, READ_PAIR_DUPLICATES, and\n" +
                    "READ_PAIR_OPTICAL_DUPLICATES.\n" +
                    "\n" +
                    "Usage example: java -jar picard.jar MarkDuplicates I=input.bam \\\n" +
                    "                 O=marked_duplicates.bam M=marked_dup_metrics.txt\n" +
                    "\n" +
                    "The program can take either coordinate-sorted or query-sorted input, however the behavior is slightly different.\n" +
                    "When the input is coordinate-sorted, unmapped mates of mapped records, and supplementary and secondary alignments are not\n" +
                    "marked as duplicates. When the input is query-sorted (actually query-grouped) then unmapped mates get marked as their mapped\n" +
                    "counter-parts, and secondary and supplementary reads get marked as the primary records with the same query-name.\n";

    /** Enum used to control how duplicates are flagged in the DT optional tag on each read. */
    public enum DuplicateTaggingPolicy { DontTag, OpticalOnly, All }

    /** The optional attribute in SAM/BAM files used to store the duplicate type. */
    public static final String DUPLICATE_TYPE_TAG = "DT";
    /** The duplicate type tag value for duplicate type: library. */
    public static final String DUPLICATE_TYPE_LIBRARY = "LB";
    /** The duplicate type tag value for duplicate type: sequencing (optical & pad-hopping, or "co-localized"). */
    public static final String DUPLICATE_TYPE_SEQUENCING = "SQ";
    /** The attribute in the SAM/BAM file used to store which read was selected as representative in a duplicate set */
    public static final String REPRESENTATIVE_READ_TAG = "RR";
    /** The attribute in the SAM/BAM file used to store the size of a duplicate set */
    public static final String DUPLICATE_SET_SIZE_TAG = "DS";

    /** Enum for the possible values that a duplicate read can be tagged with in the DT attribute. */
    public enum DuplicateType {
        LIBRARY(DUPLICATE_TYPE_LIBRARY),
        SEQUENCING(DUPLICATE_TYPE_SEQUENCING);

        private final String code;
        DuplicateType(final String code) { this.code = code; }
        public String code() { return this.code; }
    }

    private final Log log = Log.getInstance(MarkDuplicates.class);

    /**
     * If more than this many sequences in SAM file, don't spill to disk because there will not
     * be enough file handles.
     */
    @Option(shortName = "MAX_SEQS",
            doc = "This option is obsolete. ReadEnds will always be spilled to disk.")
    public int MAX_SEQUENCES_FOR_DISK_READ_ENDS_MAP = 50000;

    @Option(shortName = "MAX_FILE_HANDLES",
            doc = "Maximum number of file handles to keep open when spilling read ends to disk. " +
                    "Set this number a little lower than the per-process maximum number of file that may be open. " +
                    "This number can be found by executing the 'ulimit -n' command on a Unix system.")
    public int MAX_FILE_HANDLES_FOR_READ_ENDS_MAP = 8000;

    @Option(doc = "This number, plus the maximum RAM available to the JVM, determine the memory footprint used by " +
            "some of the sorting collections.  If you are running out of memory, try reducing this number.")
    public double SORTING_COLLECTION_SIZE_RATIO = 0.25;

    @Option(doc = "Barcode SAM tag (ex. BC for 10X Genomics)", optional = true)
    public String BARCODE_TAG = null;

    @Option(doc = "Read one barcode SAM tag (ex. BX for 10X Genomics)", optional = true)
    public String READ_ONE_BARCODE_TAG = null;

    @Option(doc = "Read two barcode SAM tag (ex. BX for 10X Genomics)", optional = true)
    public String READ_TWO_BARCODE_TAG = null;

    @Option(doc = "If a read is marked as duplicate, tag the read with representative read that was " +
            "selected out of the duplicate set.", optional = true)
    public boolean TAG_REPRESENTATIVE_READ = false;

    @Option(doc = "If true remove 'optical' duplicates and other duplicates that appear to have arisen from the " +
            "sequencing process instead of the library preparation process, even if REMOVE_DUPLICATES is false. " +
            "If REMOVE_DUPLICATES is true, all duplicates are removed and this option is ignored.")
    public boolean REMOVE_SEQUENCING_DUPLICATES = false;

    @Option(doc= "Determines how duplicate types are recorded in the DT optional attribute.")
    public DuplicateTaggingPolicy TAGGING_POLICY = DuplicateTaggingPolicy.DontTag;

    private SortingCollection<ReadEndsForMarkDuplicates> pairSort;
    private SortingCollection<ReadEndsForMarkDuplicates> fragSort;
    private SortingLongCollection duplicateIndexes;
    private SortingLongCollection opticalDuplicateIndexes;
    private SortingCollection<ReadNameInterface> representativeReadsForDuplicates;
    private ArrayList<Integer> duplicateSetSizes;

    private int numDuplicateIndices = 0;
    static private final long NO_SUCH_INDEX = Long.MAX_VALUE; // needs to be large so that that >= test fails for query-sorted traversal

    protected LibraryIdGenerator libraryIdGenerator = null; // this is initialized in buildSortedReadEndLists

    private int getBarcodeValue(final SAMRecord record) {
        return EstimateLibraryComplexity.getReadBarcodeValue(record, BARCODE_TAG);
    }

    private int getReadOneBarcodeValue(final SAMRecord record) {
        return EstimateLibraryComplexity.getReadBarcodeValue(record, READ_ONE_BARCODE_TAG);
    }

    private int getReadTwoBarcodeValue(final SAMRecord record) {
        return EstimateLibraryComplexity.getReadBarcodeValue(record, READ_TWO_BARCODE_TAG);
    }

    public MarkDuplicates() {
        DUPLICATE_SCORING_STRATEGY = ScoringStrategy.SUM_OF_BASE_QUALITIES;
    }

    /** Stock main method. */
    public static void main(final String[] args) {
        new MarkDuplicates().instanceMainWithExit(args);
    }

    /**
     * Main work method.  Reads the BAM file once and collects sorted information about
     * the 5' ends of both ends of each read (or just one end in the case of pairs).
     * Then makes a pass through those determining duplicates before re-reading the
     * input file and writing it out with duplication flags set correctly.
     */
    protected int doWork() {
        IOUtil.assertInputsAreValid(INPUT);
        IOUtil.assertFileIsWritable(OUTPUT);
        IOUtil.assertFileIsWritable(METRICS_FILE);

        final boolean useBarcodes = (null != BARCODE_TAG || null != READ_ONE_BARCODE_TAG || null != READ_TWO_BARCODE_TAG);

                reportMemoryStats("Start of doWork");
        log.info("Reading input file and constructing read end information.");
        buildSortedReadEndLists(useBarcodes);
        reportMemoryStats("After buildSortedReadEndLists");
        generateDuplicateIndexes(useBarcodes, this.REMOVE_SEQUENCING_DUPLICATES || this.TAGGING_POLICY != DuplicateTaggingPolicy.DontTag);
        reportMemoryStats("After generateDuplicateIndexes");
        log.info("Marking " + this.numDuplicateIndices + " records as duplicates.");

        if (this.READ_NAME_REGEX == null) {
            log.warn("Skipped optical duplicate cluster discovery; library size estimation may be inaccurate!");
        } else {
            log.info("Found " + (this.libraryIdGenerator.getNumberOfOpticalDuplicateClusters()) + " optical duplicate clusters.");
        }

        final SamHeaderAndIterator headerAndIterator = openInputs();
        final SAMFileHeader header = headerAndIterator.header;
        final SAMFileHeader.SortOrder sortOrder = header.getSortOrder();

        final SAMFileHeader outputHeader = header.clone();


        log.info("Reads are assumed to be ordered by: " + sortOrder);

        if (sortOrder != SAMFileHeader.SortOrder.coordinate && sortOrder != SAMFileHeader.SortOrder.queryname) {
            throw new PicardException("This program requires input that are either coordinate or query sorted. " +
                    "Found "+ sortOrder);
        }

        COMMENT.forEach(outputHeader::addComment);

        // Key: previous PG ID on a SAM Record (or null).  Value: New PG ID to replace it.
        final Map<String, String> chainedPgIds = getChainedPgIds(outputHeader);

        final SAMFileWriter out = new SAMFileWriterFactory().makeSAMOrBAMWriter(outputHeader,
                true,
                OUTPUT);

        // Now copy over the file while marking all the necessary indexes as duplicates
        long recordInFileIndex = 0;
        long nextOpticalDuplicateIndex = this.opticalDuplicateIndexes != null && this.opticalDuplicateIndexes.hasNext() ? this.opticalDuplicateIndexes.next() : NO_SUCH_INDEX;
        long nextDuplicateIndex = (this.duplicateIndexes.hasNext() ? this.duplicateIndexes.next() : NO_SUCH_INDEX);

        // initialize variables for optional representative read tagging
        CloseableIterator<ReadNameInterface> representativeReadInterator = null;
        ReadNameInterface rni = null;
        String representativeReadName = null;
        int duplicateSetSize = -1;
        int nextRepresentativeIndex = -1;
        if (TAG_REPRESENTATIVE_READ) {
            representativeReadInterator = this.representativeReadsForDuplicates.iterator();
            rni = representativeReadInterator.next();
            nextRepresentativeIndex = rni.read1IndexInFile;
            representativeReadName = rni.readname;
            duplicateSetSize = rni.setSize;
        }

        final ProgressLogger progress = new ProgressLogger(log, (int) 1e7, "Written");
        final CloseableIterator<SAMRecord> iterator = headerAndIterator.iterator;
        String duplicateQueryName = null;
        String opticalDuplicateQueryName = null;

        while (iterator.hasNext()) {
            final SAMRecord rec = iterator.next();

                final String library = LibraryIdGenerator.getLibraryName(header, rec);
                DuplicationMetrics metrics = libraryIdGenerator.getMetricsByLibrary(library);
                if (metrics == null) {
                    metrics = new DuplicationMetrics();
                    metrics.LIBRARY = library;
                    libraryIdGenerator.addMetricsByLibrary(library, metrics);
                }

                // First bring the simple metrics up to date
                if (rec.getReadUnmappedFlag()) {
                    ++metrics.UNMAPPED_READS;
                } else if(rec.isSecondaryOrSupplementary()) {
                    ++metrics.SECONDARY_OR_SUPPLEMENTARY_RDS;
                } else if (!rec.getReadPairedFlag() || rec.getMateUnmappedFlag()) {
                    ++metrics.UNPAIRED_READS_EXAMINED;
                } else {
                    ++metrics.READ_PAIRS_EXAMINED; // will need to be divided by 2 at the end
                }

            // Now try and figure out the next duplicate index (if going by coordinate. if going by query name, only do this
            // if the query name has changed.
            final boolean needNextDuplicateIndex = recordInFileIndex > nextDuplicateIndex &&
                    (sortOrder == SAMFileHeader.SortOrder.coordinate || !rec.getReadName().equals(duplicateQueryName));

            if (needNextDuplicateIndex) {
                    nextDuplicateIndex = (this.duplicateIndexes.hasNext() ? this.duplicateIndexes.next() : NO_SUCH_INDEX);
            }

            final boolean isDuplicate = recordInFileIndex == nextDuplicateIndex ||
                    (sortOrder == SAMFileHeader.SortOrder.queryname &&
                    recordInFileIndex > nextDuplicateIndex && rec.getReadName().equals(duplicateQueryName));


                if (isDuplicate) {
                    duplicateQueryName = rec.getReadName();
                    rec.setDuplicateReadFlag(true);

                    // only update duplicate counts for "decider" reads, not tag-a-long reads
                    if (!rec.isSecondaryOrSupplementary() && !rec.getReadUnmappedFlag()) {
                        // Update the duplication metrics
                        if (!rec.getReadPairedFlag() || rec.getMateUnmappedFlag()) {
                            ++metrics.UNPAIRED_READ_DUPLICATES;
                        } else {
                            ++metrics.READ_PAIR_DUPLICATES;// will need to be divided by 2 at the end
                        }
                    }
                } else {
                    rec.setDuplicateReadFlag(false);
                }

            // Manage the flagging of optical/sequencing duplicates
            final boolean needNextOpticalDuplicateIndex = recordInFileIndex > nextOpticalDuplicateIndex &&
                    (sortOrder == SAMFileHeader.SortOrder.coordinate || !rec.getReadName().equals(opticalDuplicateQueryName));

            // Possibly figure out the next opticalDuplicate index (if going by coordinate, if going by query name, only do this
            // if the query name has changed)
            if (needNextOpticalDuplicateIndex) {
                nextOpticalDuplicateIndex = (this.opticalDuplicateIndexes.hasNext() ? this.opticalDuplicateIndexes.next() : NO_SUCH_INDEX);
            }

            final boolean isOpticalDuplicate = sortOrder == SAMFileHeader.SortOrder.queryname &&
                    recordInFileIndex > nextOpticalDuplicateIndex &&
                    rec.getReadName().equals(opticalDuplicateQueryName) ||
                    recordInFileIndex == nextOpticalDuplicateIndex;

            rec.setAttribute(DUPLICATE_TYPE_TAG, null);

            if (this.TAGGING_POLICY != DuplicateTaggingPolicy.DontTag && rec.getDuplicateReadFlag()) {
                if (isOpticalDuplicate) {
                    opticalDuplicateQueryName = rec.getReadName();
                    rec.setAttribute(DUPLICATE_TYPE_TAG, DuplicateType.SEQUENCING.code());
                } else if (this.TAGGING_POLICY == DuplicateTaggingPolicy.All) {
                    rec.setAttribute(DUPLICATE_TYPE_TAG, DuplicateType.LIBRARY.code());
                }
            }

            // identify any read pair that was in a duplicate set
            if (TAG_REPRESENTATIVE_READ) {
                final boolean needNextRepresentativeIndex = recordInFileIndex > nextRepresentativeIndex &&
                        (sortOrder == SAMFileHeader.SortOrder.coordinate);
                if (needNextRepresentativeIndex && representativeReadInterator.hasNext()) {
                    rni = representativeReadInterator.next();
                    nextRepresentativeIndex = rni.read1IndexInFile;
                    representativeReadName = rni.readname;
                    duplicateSetSize = rni.setSize;
                }
                final boolean isInDuplicateSet = recordInFileIndex == nextRepresentativeIndex ||
                        (sortOrder == SAMFileHeader.SortOrder.queryname &&
                                recordInFileIndex > nextDuplicateIndex);
                if (isInDuplicateSet) {
                    if (!rec.isSecondaryOrSupplementary() && !rec.getReadUnmappedFlag()) {
                        if (TAG_REPRESENTATIVE_READ) {
                            rec.setAttribute(REPRESENTATIVE_READ_TAG, representativeReadName);
                            rec.setAttribute(DUPLICATE_SET_SIZE_TAG, duplicateSetSize);
                        }
                    }
                }
            }

            // Output the record if desired and bump the record index
            recordInFileIndex++;
            if (this.REMOVE_DUPLICATES            && rec.getDuplicateReadFlag()) continue;
            if (this.REMOVE_SEQUENCING_DUPLICATES && isOpticalDuplicate)         continue;

            if (PROGRAM_RECORD_ID != null)  rec.setAttribute(SAMTag.PG.name(), chainedPgIds.get(rec.getStringAttribute(SAMTag.PG.name())));
            out.addAlignment(rec);
            progress.record(rec);
        }

        // remember to close the inputs
        iterator.close();

        this.duplicateIndexes.cleanup();
        if (TAG_REPRESENTATIVE_READ){
            this.representativeReadsForDuplicates.cleanup();
        }

        reportMemoryStats("Before output close");
        out.close();
        reportMemoryStats("After output close");

        // Write out the metrics
        finalizeAndWriteMetrics(libraryIdGenerator);

        return 0;
    }

    /**
     * package-visible for testing
     */
    long numOpticalDuplicates() { return ((long) this.libraryIdGenerator.getOpticalDuplicatesByLibraryIdMap().getSumOfValues()); } // cast as long due to returning a double

    /** Print out some quick JVM memory stats. */
    private void reportMemoryStats(final String stage) {
        System.gc();
        final Runtime runtime = Runtime.getRuntime();
        log.info(stage + " freeMemory: " + runtime.freeMemory() + "; totalMemory: " + runtime.totalMemory() +
                "; maxMemory: " + runtime.maxMemory());
    }

    /**
     * Goes through all the records in a file and generates a set of ReadEndsForMarkDuplicates objects that
     * hold the necessary information (reference sequence, 5' read coordinate) to do
     * duplication, caching to disk as necessary to sort them.
     */
    private void buildSortedReadEndLists(final boolean useBarcodes) {
        final int sizeInBytes;
        if (useBarcodes) {
            sizeInBytes = ReadEndsForMarkDuplicatesWithBarcodes.getSizeOf();
        } else if (TAG_REPRESENTATIVE_READ) {
            sizeInBytes = ReadEndsForMarkDuplicatesSetSizeTags.getSizeOf();
        } else {
            sizeInBytes = ReadEndsForMarkDuplicates.getSizeOf();
        }
        MAX_RECORDS_IN_RAM = (int) (Runtime.getRuntime().maxMemory() / sizeInBytes) / 2;
        final int maxInMemory = (int) ((Runtime.getRuntime().maxMemory() * SORTING_COLLECTION_SIZE_RATIO) / sizeInBytes);
        log.info("Will retain up to " + maxInMemory + " data points before spilling to disk.");

        final ReadEndsForMarkDuplicatesCodec fragCodec, pairCodec, diskCodec;
        if (useBarcodes) {
            fragCodec = new ReadEndsForMarkDuplicatesWithBarcodesCodec();
            pairCodec = new ReadEndsForMarkDuplicatesWithBarcodesCodec();
            diskCodec = new ReadEndsForMarkDuplicatesWithBarcodesCodec();
        } else if (TAG_REPRESENTATIVE_READ) {
            fragCodec = new ReadEndsForMarkDuplicatesSetSizeTagsCodec();
            pairCodec = new ReadEndsForMarkDuplicatesSetSizeTagsCodec();
            diskCodec = new ReadEndsForMarkDuplicatesSetSizeTagsCodec();
        } else {
            fragCodec = new ReadEndsForMarkDuplicatesCodec();
            pairCodec = new ReadEndsForMarkDuplicatesCodec();
            diskCodec = new ReadEndsForMarkDuplicatesCodec();
        }

        this.pairSort = SortingCollection.newInstance(ReadEndsForMarkDuplicates.class,
                pairCodec,
                new ReadEndsMDComparator(useBarcodes),
                maxInMemory,
                TMP_DIR);

        this.fragSort = SortingCollection.newInstance(ReadEndsForMarkDuplicates.class,
                fragCodec,
                new ReadEndsMDComparator(useBarcodes),
                maxInMemory,
                TMP_DIR);

        final SamHeaderAndIterator headerAndIterator = openInputs();
        final SAMFileHeader.SortOrder assumedSortOrder = headerAndIterator.header.getSortOrder();
        final SAMFileHeader header = headerAndIterator.header;
        final ReadEndsForMarkDuplicatesMap tmp = new DiskBasedReadEndsForMarkDuplicatesMap(MAX_FILE_HANDLES_FOR_READ_ENDS_MAP, diskCodec);
        long index = 0;
        final ProgressLogger progress = new ProgressLogger(log, (int) 1e6, "Read");
        final CloseableIterator<SAMRecord> iterator = headerAndIterator.iterator;

        if (null == this.libraryIdGenerator) {
            this.libraryIdGenerator = new LibraryIdGenerator(header);
        }

        String duplicateQueryName = null;
        long duplicateIndex = NO_SUCH_INDEX;
        while (iterator.hasNext()) {
            final SAMRecord rec = iterator.next();

            // This doesn't have anything to do with building sorted ReadEnd lists, but it can be done in the same pass
            // over the input
            if (PROGRAM_RECORD_ID != null) {
                // Gather all PG IDs seen in merged input files in first pass.  These are gathered for two reasons:
                // - to know how many different PG records to create to represent this program invocation.
                // - to know what PG IDs are already used to avoid collisions when creating new ones.
                // Note that if there are one or more records that do not have a PG tag, then a null value
                // will be stored in this set.
                pgIdsSeen.add(rec.getStringAttribute(SAMTag.PG.name()));
            }

            // Of working in query-sorted, need to keep index of first record with any given query-name.
            if(assumedSortOrder == SAMFileHeader.SortOrder.queryname && !rec.getReadName().equals(duplicateQueryName)) {
                duplicateQueryName  = rec.getReadName();
                duplicateIndex      = index;
            }

            if (rec.getReadUnmappedFlag()) {
                if (rec.getReferenceIndex() == -1 && assumedSortOrder == SAMFileHeader.SortOrder.coordinate) {
                    // When we hit the unmapped reads with no coordinate, no reason to continue (only in coordinate sort).
                    break;
                }
                // If this read is unmapped but sorted with the mapped reads, just skip it.

            } else if (!rec.isSecondaryOrSupplementary()) {
                final long indexForRead = assumedSortOrder == SAMFileHeader.SortOrder.queryname ? duplicateIndex : index;
                final ReadEndsForMarkDuplicates fragmentEnd = buildReadEnds(header, indexForRead, rec, useBarcodes);
                this.fragSort.add(fragmentEnd);

                if (rec.getReadPairedFlag() && !rec.getMateUnmappedFlag()) {
                    final String key = rec.getAttribute(ReservedTagConstants.READ_GROUP_ID) + ":" + rec.getReadName();
                    ReadEndsForMarkDuplicates pairedEnds = tmp.remove(rec.getReferenceIndex(), key);

                    // See if we've already seen the first end or not
                    if (pairedEnds == null) {
                        pairedEnds = buildReadEnds(header, indexForRead, rec, useBarcodes);
                        tmp.put(pairedEnds.read2ReferenceIndex, key, pairedEnds);
                    } else {
                        final int sequence = fragmentEnd.read1ReferenceIndex;
                        final int coordinate = fragmentEnd.read1Coordinate;

                        if (TAG_REPRESENTATIVE_READ)
                            ((ReadEndsForMarkDuplicatesSetSizeTags) pairedEnds).firstEncounteredReadName = rec.getReadName();

                        // Set orientationForOpticalDuplicates, which always goes by the first then the second end for the strands.  NB: must do this
                        // before updating the orientation later.
                        if (rec.getFirstOfPairFlag()) {
                            pairedEnds.orientationForOpticalDuplicates = ReadEnds.getOrientationByte(rec.getReadNegativeStrandFlag(), pairedEnds.orientation == ReadEnds.R);
                            if (useBarcodes)
                                ((ReadEndsForMarkDuplicatesWithBarcodes) pairedEnds).readOneBarcode = getReadOneBarcodeValue(rec);
                        } else {
                            pairedEnds.orientationForOpticalDuplicates = ReadEnds.getOrientationByte(pairedEnds.orientation == ReadEnds.R, rec.getReadNegativeStrandFlag());
                            if (useBarcodes)
                                ((ReadEndsForMarkDuplicatesWithBarcodes) pairedEnds).readTwoBarcode = getReadTwoBarcodeValue(rec);
                        }

                        // If the second read is actually later, just add the second read data, else flip the reads
                        if (sequence > pairedEnds.read1ReferenceIndex ||
                                (sequence == pairedEnds.read1ReferenceIndex && coordinate >= pairedEnds.read1Coordinate)) {
                            pairedEnds.read2ReferenceIndex = sequence;
                            pairedEnds.read2Coordinate = coordinate;
                            pairedEnds.read2IndexInFile = indexForRead;
                            pairedEnds.orientation = ReadEnds.getOrientationByte(pairedEnds.orientation == ReadEnds.R,
                                    rec.getReadNegativeStrandFlag());
                        } else {
                            pairedEnds.read2ReferenceIndex = pairedEnds.read1ReferenceIndex;
                            pairedEnds.read2Coordinate = pairedEnds.read1Coordinate;
                            pairedEnds.read2IndexInFile = pairedEnds.read1IndexInFile;
                            pairedEnds.read1ReferenceIndex = sequence;
                            pairedEnds.read1Coordinate = coordinate;
                            pairedEnds.read1IndexInFile = indexForRead;
                            pairedEnds.orientation = ReadEnds.getOrientationByte(rec.getReadNegativeStrandFlag(),
                                    pairedEnds.orientation == ReadEnds.R);
                        }
                        pairedEnds.score += DuplicateScoringStrategy.computeDuplicateScore(rec, this.DUPLICATE_SCORING_STRATEGY);
                        this.pairSort.add(pairedEnds);
                    }
                }
            }

            // Print out some stats every 1m reads
            ++index;
            if (progress.record(rec)) {
                log.info("Tracking " + tmp.size() + " as yet unmatched pairs. " + tmp.sizeInRam() + " records in RAM.");
            }
        }

        log.info("Read " + index + " records. " + tmp.size() + " pairs never matched.");
        iterator.close();

        // Tell these collections to free up memory if possible.
        this.pairSort.doneAdding();
        this.fragSort.doneAdding();
    }

    /** Builds a read ends object that represents a single read. */
    private ReadEndsForMarkDuplicates buildReadEnds(final SAMFileHeader header, final long index, final SAMRecord rec, final boolean useBarcodes) {
        final ReadEndsForMarkDuplicates ends;

        if (useBarcodes) {
            ends = new ReadEndsForMarkDuplicatesWithBarcodes();
        } else if (TAG_REPRESENTATIVE_READ) {
            ends = new ReadEndsForMarkDuplicatesSetSizeTags();
        } else {
            ends = new ReadEndsForMarkDuplicates();
        }
        ends.read1ReferenceIndex = rec.getReferenceIndex();
        ends.read1Coordinate = rec.getReadNegativeStrandFlag() ? rec.getUnclippedEnd() : rec.getUnclippedStart();
        ends.orientation = rec.getReadNegativeStrandFlag() ? ReadEnds.R : ReadEnds.F;
        ends.read1IndexInFile = index;
        ends.score = DuplicateScoringStrategy.computeDuplicateScore(rec, this.DUPLICATE_SCORING_STRATEGY);

        // Doing this lets the ends object know that it's part of a pair
        if (rec.getReadPairedFlag() && !rec.getMateUnmappedFlag()) {
            ends.read2ReferenceIndex = rec.getMateReferenceIndex();
        }

        // Fill in the library ID
        ends.libraryId = libraryIdGenerator.getLibraryId(rec);

        // Fill in the location information for optical duplicates
        if (this.opticalDuplicateFinder.addLocationInformation(rec.getReadName(), ends)) {
            // calculate the RG number (nth in list)
            ends.readGroup = 0;
            final String rg = (String) rec.getAttribute("RG");
            final List<SAMReadGroupRecord> readGroups = header.getReadGroups();

            if (rg != null && readGroups != null) {
                for (final SAMReadGroupRecord readGroup : readGroups) {
                    if (readGroup.getReadGroupId().equals(rg)) break;
                    else ends.readGroup++;
                }
            }
        }

        if (useBarcodes) {
            final ReadEndsForMarkDuplicatesWithBarcodes endsWithBarcode = (ReadEndsForMarkDuplicatesWithBarcodes) ends;
            endsWithBarcode.barcode = getBarcodeValue(rec);
            if (!rec.getReadPairedFlag() || rec.getFirstOfPairFlag()) {
                endsWithBarcode.readOneBarcode = getReadOneBarcodeValue(rec);
            } else {
                endsWithBarcode.readTwoBarcode = getReadTwoBarcodeValue(rec);
            }
        }

        return ends;
    }

    /**
     * Goes through the accumulated ReadEndsForMarkDuplicates objects and determines which of them are
     * to be marked as duplicates.
     *
     * @return an array with an ordered list of indexes into the source file
     */
    private void generateDuplicateIndexes(final boolean useBarcodes, final boolean indexOpticalDuplicates) {
        // Keep this number from getting too large even if there is a huge heap.
        int maxInMemory = (int) Math.min((Runtime.getRuntime().maxMemory() * 0.25) / SortingLongCollection.SIZEOF, (double) (Integer.MAX_VALUE - 5));
        // If we're also tracking optical duplicates, cut maxInMemory in half, since we'll need two sorting collections
        if (indexOpticalDuplicates) {
            maxInMemory /= 2;
            this.opticalDuplicateIndexes = new SortingLongCollection(maxInMemory, TMP_DIR.toArray(new File[TMP_DIR.size()]));
        }
        // If we're are tracking representative reads, adjust maInMemory
        if (TAG_REPRESENTATIVE_READ) {
            // Memory requirements:
            // 1) two long entries (for duplicateIndexes and opticalDuplicateIndexes): 8+8
            // 2) two int entries and string of 34 UTF-8 characters (for ReadNameInterface): 32+32+(8 * 34)+4
            maxInMemory = (int) (Runtime.getRuntime().maxMemory() * 0.25) / 356;
        }
        log.info("Will retain up to " + maxInMemory + " duplicate indices before spilling to disk.");
        this.duplicateIndexes = new SortingLongCollection(maxInMemory, TMP_DIR.toArray(new File[TMP_DIR.size()]));
        if (TAG_REPRESENTATIVE_READ){
            final RepresentativeReadCodec representativeReadCodec = new RepresentativeReadCodec();
            this.representativeReadsForDuplicates = SortingCollection.newInstance(ReadNameInterface.class,
                    representativeReadCodec,
                    new RepresentativeREadComparator(),
                    maxInMemory,
                    TMP_DIR);
            this.duplicateSetSizes = new ArrayList<>();
        }

        ReadEndsForMarkDuplicates firstOfNextChunk = null;
        final List nextChunk;
        if (TAG_REPRESENTATIVE_READ) {
            firstOfNextChunk = new ReadEndsForMarkDuplicatesSetSizeTags();
            nextChunk = new ArrayList<ReadEndsForMarkDuplicatesSetSizeTags>(200);
        }
        else {
            nextChunk = new ArrayList<ReadEndsForMarkDuplicates>(200);
        }

        // First just do the pairs
        log.info("Traversing read pair information and detecting duplicates.");
        for (final ReadEndsForMarkDuplicates next : this.pairSort) {
            if (firstOfNextChunk != null && areComparableForDuplicates(firstOfNextChunk, next, true, useBarcodes)) {
                nextChunk.add(next);
            } else {
                if (nextChunk.size() > 1) {
                    markDuplicatePairs(nextChunk);
                    if (TAG_REPRESENTATIVE_READ) markRepresentativeRead(nextChunk);
                }
                nextChunk.clear();
                nextChunk.add(next);
                firstOfNextChunk = next;
            }
        }
        if (nextChunk.size() > 1) {
            markDuplicatePairs(nextChunk);
            if (TAG_REPRESENTATIVE_READ) markRepresentativeRead(nextChunk);
        }
        this.pairSort.cleanup();
        this.pairSort = null;

        // Now deal with the fragments
        log.info("Traversing fragment information and detecting duplicates.");
        boolean containsPairs = false;
        boolean containsFrags = false;

        firstOfNextChunk = null;

        for (final ReadEndsForMarkDuplicates next : this.fragSort) {
            if (firstOfNextChunk != null && areComparableForDuplicates(firstOfNextChunk, next, false, useBarcodes)) {
                nextChunk.add(next);
                containsPairs = containsPairs || next.isPaired();
                containsFrags = containsFrags || !next.isPaired();
            } else {
                if (nextChunk.size() > 1 && containsFrags) {
                    markDuplicateFragments(nextChunk, containsPairs);
                }
                nextChunk.clear();
                nextChunk.add(next);
                firstOfNextChunk = next;
                containsPairs = next.isPaired();
                containsFrags = !next.isPaired();
            }
        }
        markDuplicateFragments(nextChunk, containsPairs);
        this.fragSort.cleanup();
        this.fragSort = null;

        log.info("Sorting list of duplicate records.");
        this.duplicateIndexes.doneAddingStartIteration();
        if (this.opticalDuplicateIndexes != null) this.opticalDuplicateIndexes.doneAddingStartIteration();
        if (TAG_REPRESENTATIVE_READ) this.representativeReadsForDuplicates.doneAdding();
    }

    private boolean areComparableForDuplicates(final ReadEndsForMarkDuplicates lhs, final ReadEndsForMarkDuplicates rhs, final boolean compareRead2, final boolean useBarcodes) {
        boolean areComparable = lhs.libraryId == rhs.libraryId;

        if (useBarcodes && areComparable) { // areComparable is useful here to avoid the casts below
            final ReadEndsForMarkDuplicatesWithBarcodes lhsWithBarcodes = (ReadEndsForMarkDuplicatesWithBarcodes) lhs;
            final ReadEndsForMarkDuplicatesWithBarcodes rhsWithBarcodes = (ReadEndsForMarkDuplicatesWithBarcodes) rhs;
            areComparable = lhsWithBarcodes.barcode == rhsWithBarcodes.barcode &&
                    lhsWithBarcodes.readOneBarcode == rhsWithBarcodes.readOneBarcode &&
                    lhsWithBarcodes.readTwoBarcode == rhsWithBarcodes.readTwoBarcode;
        }

        if (areComparable) {
            areComparable = lhs.read1ReferenceIndex == rhs.read1ReferenceIndex &&
                    lhs.read1Coordinate == rhs.read1Coordinate &&
                    lhs.orientation == rhs.orientation;
        }

        if (areComparable && compareRead2) {
            areComparable = lhs.read2ReferenceIndex == rhs.read2ReferenceIndex &&
                    lhs.read2Coordinate == rhs.read2Coordinate;
        }

        return areComparable;
    }

    private void addIndexAsDuplicate(final long bamIndex) {
        this.duplicateIndexes.add(bamIndex);
        ++this.numDuplicateIndices;
    }

    private void addRepresentativeReadOfDuplicateSet(final String recID, final int setSize, final long read1IndexInFile) {
        final ReadNameInterface rni = new ReadNameInterface();
        rni.readname = recID;
        rni.setSize = setSize;
        rni.read1IndexInFile = (int) read1IndexInFile;
        this.representativeReadsForDuplicates.add(rni);
    }

    /**
     * Takes a list of ReadEndsForMarkDuplicatesSetSizeTags objects and marks the representative read name
     * for the set
     *
     * @param list
     */
    private void markRepresentativeRead(final List<ReadEndsForMarkDuplicatesSetSizeTags> list) {
        short maxScore = 0;
        ReadEndsForMarkDuplicatesSetSizeTags best = null;

        /** All read ends should have orientation FF, FR, RF, or RR **/
        for (final ReadEndsForMarkDuplicatesSetSizeTags end : list) {
            if (end.score > maxScore || best == null) {
                maxScore = end.score;
                best = end;
            }
        }

        // for read name (for representative read name), add the last of the pair that was examined
        for (final ReadEndsForMarkDuplicatesSetSizeTags end : list) {
            addRepresentativeReadOfDuplicateSet(best.firstEncounteredReadName, list.size(), end.read1IndexInFile);
        }

    }


    /**
     * Takes a list of ReadEndsForMarkDuplicates objects and removes from it all objects that should
     * not be marked as duplicates.  This assumes that the list contains objects representing pairs.
     *
     * @param list
     */
     //private void markDuplicatePairs(final List<? extends ReadEnds> list) {
    private void markDuplicatePairs(final List<ReadEndsForMarkDuplicates> list) {
        short maxScore = 0;
        ReadEndsForMarkDuplicates best = null;

        /** All read ends should have orientation FF, FR, RF, or RR **/
        for (final ReadEndsForMarkDuplicates end : list) {
            if (end.score > maxScore || best == null) {
                maxScore = end.score;
                best = end;
            }
        }

        if (this.READ_NAME_REGEX != null) {
            AbstractMarkDuplicatesCommandLineProgram.trackOpticalDuplicates(list, best, opticalDuplicateFinder, libraryIdGenerator);
        }

        for (final ReadEndsForMarkDuplicates end : list) {
            if (end != best) {
                addIndexAsDuplicate(end.read1IndexInFile);

                // in query-sorted case, these will be the same.
                // TODO: also in coordinate sorted, when one read is unmapped
                if(end.read2IndexInFile != end.read1IndexInFile) addIndexAsDuplicate(end.read2IndexInFile);

                if (end.isOpticalDuplicate && this.opticalDuplicateIndexes != null) {
                    this.opticalDuplicateIndexes.add(end.read1IndexInFile);
                    this.opticalDuplicateIndexes.add(end.read2IndexInFile);
                }
            }
        }
    }

    /**
     * Takes a list of ReadEndsForMarkDuplicates objects and removes from it all objects that should
     * not be marked as duplicates.  This will set the duplicate index for only list items are fragments.
     *
     * @param list
     * @param containsPairs true if the list also contains objects containing pairs, false otherwise.
     */
    private void markDuplicateFragments(final List<ReadEndsForMarkDuplicates> list, final boolean containsPairs) {
        if (containsPairs) {
            for (final ReadEndsForMarkDuplicates end : list) {
                if (!end.isPaired()) addIndexAsDuplicate(end.read1IndexInFile);
            }
        } else {
            short maxScore = 0;
            ReadEndsForMarkDuplicates best = null;
            for (final ReadEndsForMarkDuplicates end : list) {
                if (end.score > maxScore || best == null) {
                    maxScore = end.score;
                    best = end;
                }
            }

            for (final ReadEndsForMarkDuplicates end : list) {
                if (end != best) {
                    addIndexAsDuplicate(end.read1IndexInFile);
                }
            }
        }
    }

    // To avoid overflows or underflows when subtracting two large (positive and negative) numbers
    static int compareInteger(final int x, final int y) {
        return (x < y) ? -1 : ((x == y) ? 0 : 1);
    }

    /** Comparator for ReadEndsForMarkDuplicates that orders by read1 position then pair orientation then read2 position. */
    static class ReadEndsMDComparator implements Comparator<ReadEndsForMarkDuplicates> {

        final boolean useBarcodes;

        public ReadEndsMDComparator(final boolean useBarcodes) {
            this.useBarcodes = useBarcodes;
        }

        public int compare(final ReadEndsForMarkDuplicates lhs, final ReadEndsForMarkDuplicates rhs) {
            int compareDifference = lhs.libraryId - rhs.libraryId;
            if (useBarcodes) {
                final ReadEndsForMarkDuplicatesWithBarcodes lhsWithBarcodes = (ReadEndsForMarkDuplicatesWithBarcodes) lhs;
                final ReadEndsForMarkDuplicatesWithBarcodes rhsWithBarcodes = (ReadEndsForMarkDuplicatesWithBarcodes) rhs;
                if (compareDifference == 0) compareDifference = compareInteger(lhsWithBarcodes.barcode, rhsWithBarcodes.barcode);
                if (compareDifference == 0) compareDifference = compareInteger(lhsWithBarcodes.readOneBarcode, rhsWithBarcodes.readOneBarcode);
                if (compareDifference == 0) compareDifference = compareInteger(lhsWithBarcodes.readTwoBarcode, rhsWithBarcodes.readTwoBarcode);
            }
            if (compareDifference == 0) compareDifference = lhs.read1ReferenceIndex - rhs.read1ReferenceIndex;
            if (compareDifference == 0) compareDifference = lhs.read1Coordinate - rhs.read1Coordinate;
            if (compareDifference == 0) compareDifference = lhs.orientation - rhs.orientation;
            if (compareDifference == 0) compareDifference = lhs.read2ReferenceIndex - rhs.read2ReferenceIndex;
            if (compareDifference == 0) compareDifference = lhs.read2Coordinate - rhs.read2Coordinate;
            if (compareDifference == 0) compareDifference = (int) (lhs.read1IndexInFile - rhs.read1IndexInFile);
            if (compareDifference == 0) compareDifference = (int) (lhs.read2IndexInFile - rhs.read2IndexInFile);

            return compareDifference;
        }
    }

    // order representative read entries based on the record index
    static class RepresentativeREadComparator implements Comparator<ReadNameInterface> {

        public RepresentativeREadComparator() {}

        public int compare(final ReadNameInterface lhs, final ReadNameInterface rhs) {
            int compareDifference = lhs.read1IndexInFile - rhs.read1IndexInFile;
            return compareDifference;
        }
    }


}
