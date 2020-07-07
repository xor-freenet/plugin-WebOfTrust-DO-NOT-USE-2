/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.io.Closer;
import plugins.WebOfTrust.network.input.IdentityDownloader;
import plugins.WebOfTrust.util.RingBuffer;
import plugins.WebOfTrust.util.Pair;
import plugins.WebOfTrust.util.jobs.BackgroundJob;


/**
 * The {@link IdentityFetcher} fetches the data files of all known identities, which the
 * {@link IdentityFileProcessor} then imports into the database. Since the network usually delivers
 * the files faster than they can be processed, this queue is responsible for storing the files in
 * between fetching and processing.<br><br>
 * 
 * The primary key of each data file is the combination of the {@link Identity} which published it,
 * and the {@link Identity#getRawEdition() edition} (= version) of the file (Notice: These are
 * implicitly identified by a single {@link FreenetURI} object in the function signatures).
 * For each such primary key, one file can exist in the queue.<br><br>
 * 
 * For the case where the queue simultaneously holds multiple files of different editions for the
 * same {@link Identity}, two behaviors are allowed for the implementation:<br>
 * 1. Deduplication of editions: For increased performance, the queue only passes the latest edition
 *    of each {@link Identity}'s file to the {@link IdentityFileProcessor} and silently drops older
 *    editions. This is possible because the {@link Score} computations of WOT do not require old
 *    versions of the input data.<br>
 * 2. FIFO: The implementation passes every edition of the file it has received to the
 *    {@link IdentityFileProcessor}, even if this means that outdated editions will be processed.
 *    The order of the output of the queue is the same as the one of the input.
 *    This is suitable for the debugging purpose of deterministic repetition of sessions.<br><br>
 *    
 * Notice: Implementations do not necessarily have to be disk-based, the word "file" is only used
 * to name the data set of an {@link Identity} in an easy to understand way.
 */
public interface IdentityFileQueue {
	/**
	 * TODO: Code quality: This should be a {@link FilterInputStream} to allow replacing
	 * 
	 *     if(identityFileStream != null)
	 *         Closer.close(identityFileStream.mXMLInputStream);
	 * 
	 * with
	 * 
	 *     Closer.close(identityFileStream);
	 *     
	 * in class IdentityFileQueue (variables are named differently there, search for Closer). */
	public static final class IdentityFileStream {
		public final FreenetURI mURI;

		public final InputStream mXMLInputStream;

		/**
		 * @param uri
		 *     The {@link FreenetURI} from which the identity file was downloaded.<br>
		 *     ATTENTION: The edition in the URI must match the specific edition of the file.
		 * @param xmlInputStream
		 *     The unmodified XML data of the file.
		 */
		public IdentityFileStream(FreenetURI uri, InputStream xmlInputStream) {
			mURI = uri;
			mXMLInputStream = xmlInputStream;
		}
	}

	/** Wraps an {@link IdentityFileStream} in order to be able to call {@link InputStream#close()}
	 *  upon the IdentityFileStream **before** closing the IdentityFileStreamWrapper itself.  
	 *  I.e. calling close() upon the stream returned by
	 *  {@link IdentityFileStreamWrapper#getIdentityFileStream()} will **not** call
	 *  {@link IdentityFileStreamWrapper#close()}.
	 *  
	 *  Calling {@link IdentityFileStreamWrapper#close()} afterwards removes the file from the
	 *  {@link IdentityFileQueue}'s temporary dir for files which are being processed currently.
	 *  
	 *  This ensures users of {@link IdentityFileQueue#poll()} can read the file from the
	 *  {@link IdentityFileStream} and then keep it in the IdentityFileQueue while processing the
	 *  data; to remove it from the queue only **after** processing is finished.  
	 *  That in turn ensures {@link IdentityFileQueue#containsAnyEditionOf(FreenetURI)} will keep
	 *  returning true while the file is being processed.
	 *  
	 *  In practice this guarantees the {@link IdentityDownloaderSlow} won't download the same
	 *  IdentityFile again while it is being processed. */
	public static interface IdentityFileStreamWrapper extends Closeable {
		/** See {@link IdentityFileStreamWrapper}.
		 *   
		 *  NOTICE: Calling {@link InputStream#close()} upon the returned stream will **not**
		 *  implicitly call {@link IdentityFileStreamWrapper#close()}!  
		 *  You **must** call the latter separately afterwards! */
		public IdentityFileStream getIdentityFileStream();
		
		/** See {@link IdentityFileStreamWrapper}. */
		@Override public void close() throws IOException;
	}

	/** NOTICE: The behavior of this function currently is different among
	 *  {@link IdentityFileDiskQueue} and {@link IdentityFileMemoryQueue}! See the FIXME at
	 *  the *MemoryQueue.
	 *  
	 *  NOTICE: In the case of {@link IdentityFileDiskQueue} this accesses the filesystem and thus
	 *  might be rather slow! */
	public boolean containsAnyEditionOf(FreenetURI identityFileURI);

	public void add(IdentityFileStream file);

	/**
	 * Removes and returns element from the queue. Returns null if the queue is empty.<br><br>
	 * 
	 * ATTENTION: Concurrent processing of multiple elements from the queue is not supported.
	 * This means that the {@link InputStream} of a returned {@link IdentityFileStream} must be
	 * closed before you call {@link #poll()} the next time.*/
	public IdentityFileStream poll();

	/** Gets the number of files available for {@link #poll()}.
	 *  It is safe to use poll() without checking for the size to be non-zero before. */
	public int getSize();

	/**
	 * Registers a {@link BackgroundJob} whose {@link BackgroundJob#triggerExecution()} shall be
	 * called by the queue once an element is available for the job to {@link #poll()}.<br><br>
	 * 
	 * Will also trigger the execution of the handler if the queue already contains files. This
	 * can happen if the queue is capable of preserving files across restarts, or if this function
	 * is called after {@link #add(IdentityFileStream)}. Thus it should be safe to call this
	 * function in a lazy manner, i.e. after starting things which fill the queue.<br>
	 * Nevertheless, to ensure that developers do not completely forget about registering event
	 * handlers, implementations of {@link #add(IdentityFileStream)} may assert(false) or log an
	 * error if {@link #add(IdentityFileStream)} is called before this function. */
	public void registerEventHandler(BackgroundJob handler);

	/**
	 * @return
	 *     An {@link IdentityFileQueueStatistics} object suitable for displaying statistics
	 *     in the UI.<br>
	 *     Its data is coherent, i.e. queried in an atomic fashion.<br>
	 *     The object is a clone, you may interfere with the contents of the member variables. */
	public IdentityFileQueueStatistics getStatistics();

	/**
	 * Same as {@link #getStatistics()}, but returns the statistics of the previous run of WoT.
	 * The statistics are stored in a file separate to the main WoT db4o database so benchmarks of
	 * multiple runs with different databases can be compared against one and another.
	 * 
	 * @throws IOException
	 *     If there was no previous session, if the file storing the statistics is corrupted, or
	 *     the particular IdentityFileQueue implementation does not implement storage. */
	public IdentityFileQueueStatistics getStatisticsOfLastSession() throws IOException;

	/** Must be called by WoT upon shutdown. */
	public void stop();


	public static final class IdentityFileQueueStatistics implements Cloneable, Serializable {
		/**
		 * Count of files which were passed to {@link #add(IdentityFileStream)}.
		 * This is equal to the total number of downloaded Identity files as the contract of
		 * {@link IdentityDownloader} specifies that strictly all downloaded files are passed to the
		 * IdentityFileQueue.
		 * 
		 * Thus this includes files which:
		 * - are not queued anymore (see {@link #mFinishedFiles}).
		 * - are still queued (see {@link #mQueuedFiles}).
		 * - were deleted due to deduplication (see {@link #mDeduplicatedFiles}).
		 * - failed en-/dequeuing due to errors (see {@link #mFailedFiles}).
		 * - were left over from the last session (see {@link #mLeftoverFilesOfLastSession}).
		 * 
		 * The lost files are included to ensure that errors can be noticed by the user from
		 * statistics in the UI, and because this variable shall represent the total number of
		 * downloaded files. */
		public int mTotalQueuedFiles = 0;

		/**
		 * Count of files which have been passed to {@link #add(IdentityFileStream)} during the last
		 * time WoT was run but had not been dequeued by {@link #poll()} yet.
		 * This value is **not** decremented once the files have been processed! */
		public int mLeftoverFilesOfLastSession = 0;

		/**
		 * For each newly enqueued file, a {@link Pair} is added with {@link Pair#x} =
		 * {@link CurrentTimeUTC#getInMillis()} and {@link Pair#y} =
		 * {@link #mTotalQueuedFiles} minus {@link #mLeftoverFilesOfLastSession}.
		 * 
		 * Thus this contains the X/Y values for a plot of total downloaded Identity files - in the
		 * current session - across the uptime of WoT.
		 * 
		 * Additionally, for allowing external code to work without checks for emptiness, a first
		 * Pair is added at construction to represent the initial amount of 0 files at time of
		 * construction. */
		public RingBuffer<Pair<Long, Integer>> mTimesOfQueuing
			= new RingBuffer<>(MAX_TIMES_OF_QUEUING_SIZE);
	
		public static final int MAX_TIMES_OF_QUEUING_SIZE = 128 * 1024;
	
		/**
		 * Count of files which have been passed to {@link #add(IdentityFileStream)} but have not
		 * been dequeued by {@link #poll()} yet. */
		public int mQueuedFiles = 0;
		
		/**
		 * Count of files which are currently in processing.<br>
		 * A file is considered to be in processing when it has been dequeued using
		 * {@link IdentityFileQueue#poll()}, but the {@link InputStream} of the
		 * {@link IdentityFileStream} has not been closed yet.<br>
		 * This number should only ever be 0 or 1 as required by {@link IdentityFileQueue#poll()}.
		 * (Concurrent processing is not supported because the filenames could collide).<br><br>
		 * 
		 * Notice: Queue implementations are free to not track this number, i.e. keep it at 0.<br>
		 * Without warranty it can be said that {@link IdentityFileDiskQueue} does track this
		 * number, but {@link IdentityFileMemoryQueue} does not. */
		public int mProcessingFiles = 0;
		
		/**
		 * Count of files for which processing is finished.<br>
		 * A file is considered to be finished when it has been dequeued using
		 * {@link IdentityFileQueue#poll()} and the {@link InputStream} of the
		 * {@link IdentityFileStream} has been closed.<br>
		 * This number can be less than the files passed to {@link #add(IdentityFileStream)}:
		 * Files can be dropped due to deduplication (or errors).<br><br>
		 * 
		 * Notice: Queue implementations are free to increment this number even before the stream
		 * has been closed.<br>
		 * Without warranty it can be said that {@link IdentityFileDiskQueue} does wait for the
		 * stream to be closed, but {@link IdentityFileMemoryQueue} does not and rather increments
		 * immediately in {@link IdentityFileMemoryQueue#poll()}. */
		public int mFinishedFiles = 0;
	
		/**
		 * See {@link IdentityFileQueue}.<br>
		 * Equal to <code>{@link #mTotalQueuedFiles} - {@link #mQueuedFiles}
		 * - {@link #mProcessingFiles} - {@link #mFinishedFiles}</code>.
		 */
		public int mDeduplicatedFiles = 0;

		/** Number of files which the queue has dropped due to internal errors. These are bugs. */
		public int mFailedFiles = 0;


		/** Value of {@link CurrentTimeUTC#getInMillis()} when this object was created. */
		public final long mStartupTimeMilliseconds = CurrentTimeUTC.getInMillis();
	
		private static final long serialVersionUID = 1L;
	
	
		IdentityFileQueueStatistics() {
			mTimesOfQueuing.addLast(new Pair<>(mStartupTimeMilliseconds, 0));
		}
	
		@Override public IdentityFileQueueStatistics clone() {
			try {
				IdentityFileQueueStatistics result = (IdentityFileQueueStatistics)super.clone();
				result.mTimesOfQueuing = result.mTimesOfQueuing.clone();
				return result;
			} catch (CloneNotSupportedException e) {
				throw new RuntimeException(e);
			}
		}

		/**
		 * The average increase of {@link #mTotalQueuedFiles} per hour.<br>
		 * If no bugs are in {@link IdentityFetcher}, this is equal to the number of fetched files
		 * per hour. */
		public float getAverageQueuedFilesPerHour() {
			float uptimeSeconds
				= (float)(CurrentTimeUTC.getInMillis() - mStartupTimeMilliseconds)/1000;
			float uptimeHours = uptimeSeconds / (60*60);
			
			if(uptimeHours == 0) // prevent division by 0
				return 0;
			
			return (float)(mTotalQueuedFiles - mLeftoverFilesOfLastSession) / uptimeHours;
		}

		boolean checkConsistency() {
			return (
					(mTotalQueuedFiles >= 0)
				
				 && (mTimesOfQueuing.size() ==
				      (mTotalQueuedFiles - mLeftoverFilesOfLastSession + 1 /* for initial entry */)
					|| mTimesOfQueuing.size() == MAX_TIMES_OF_QUEUING_SIZE)
				
				 && (mQueuedFiles >= 0)
				 
				 && (mProcessingFiles >= 0)
				 
				 && (mFinishedFiles >= 0)
				 
				 && (mDeduplicatedFiles >= 0)
				 
				 && (mFailedFiles == 0)
				 
				 && (mLeftoverFilesOfLastSession <= mTotalQueuedFiles)
				
				 && (mQueuedFiles <= mTotalQueuedFiles)
				 
				 && (mProcessingFiles <= mTotalQueuedFiles)
				 
				 && (mFinishedFiles <= mTotalQueuedFiles)
				 
				 && (mDeduplicatedFiles <= mTotalQueuedFiles)
				 
				 && (mProcessingFiles <= 1)
					
				 && (mDeduplicatedFiles ==
						mTotalQueuedFiles - mQueuedFiles - mProcessingFiles - mFinishedFiles)
			 );
		}
	
		/**
		 * Uses Java serialization instead of WoT's main db4o database so statistics plots of
		 * different runs with a blank db4o database each can be compared against one and another as
		 * a benchmark. */
		void write(File file) throws IOException {
			FileOutputStream fos = null;
			ObjectOutputStream ous = null;
			
			try {
				if(file.exists())
					throw new IOException("Output file exists already: " + file);
				
				fos = new FileOutputStream(file);
				ous = new ObjectOutputStream(fos);
				ous.writeObject(this);
			} finally {
				Closer.close(ous);
				Closer.close(fos);
			}
		}
	
		/** @see #write(File) */
		static IdentityFileQueueStatistics read(File source) throws IOException {
			FileInputStream fis = null;
			ObjectInputStream ois = null;
			
			try {
				fis = new FileInputStream(source);
				ois = new ObjectInputStream(fis);
				IdentityFileQueueStatistics deserialized
					= (IdentityFileQueueStatistics)ois.readObject();
				if(deserialized == null)
					throw new IOException("No IdentityFileQueueStatistics in file: " + source);
				
				assert(deserialized.checkConsistency());
				
				return deserialized;
			} catch(ClassNotFoundException e) {
				throw new IOException(e);
			} finally {
				Closer.close(ois);
				Closer.close(fis);
			}
		}
	}
}
