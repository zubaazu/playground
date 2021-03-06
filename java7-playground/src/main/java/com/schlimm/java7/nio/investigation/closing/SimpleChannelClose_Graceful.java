package com.schlimm.java7.nio.investigation.closing;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Graceful shutdown that garantees that submitted tasks will be processed prior shutdown and that denies submission of
 * new tasks when channel is shutdown.
 * 
 * @author Niklas Schlimm
 * 
 */
public class SimpleChannelClose_Graceful {

	private static final String FILE_NAME = "E:/temp/afile.out";
	private static AtomicInteger fileindex = new AtomicInteger(0);
	private static ThreadPoolExecutor pool = new DefensiveThreadPoolExecutor(100, 100, 0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<Runnable>());
	/** The channel to the asynchronous log file */
	private static AsynchronousFileChannel GLOBAL_LOG_FILE;
	/** System wide access to log file */
	public static AtomicReference<AsynchronousFileChannel> GLOBAL_LOG_FILE_ACCESS = new AtomicReference<>();

	public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {
		GLOBAL_LOG_FILE = AsynchronousFileChannel.open(
				Paths.get(FILE_NAME),
				new HashSet<StandardOpenOption>(Arrays.asList(StandardOpenOption.WRITE, StandardOpenOption.CREATE,
						StandardOpenOption.DELETE_ON_CLOSE)), pool);
		GLOBAL_LOG_FILE_ACCESS.set(GLOBAL_LOG_FILE);

		new Thread(new Runnable() { // arbitrary thread that writes stuff into an asynchronous I/O data sink

			@Override
			public void run() {
				try {
					for (;;) {
						GLOBAL_LOG_FILE_ACCESS.get().write(ByteBuffer.wrap("Hello".getBytes()),
								fileindex.getAndIncrement() * 5);
					}
				} catch (NonWritableChannelException e) {
					System.out.println("Deal with the fact that the channel was closed asynchronously ... "
							+ e.toString());
				} catch (Exception e) {
					System.out.println("Something unexpected happened ... " + e.toString());
				}
			}
		}).start();

		Timer timer = new Timer(); // asynchronous channel closer
		timer.schedule(new TimerTask() {
			public void run() {
				closeGracefully();
			}
		}, 1000);

	}

	/**
	 * Avoid race condition of closing thread and "last" task of the queue that issues the "isEmpty" event.
	 */
	private static Lock closeLock = new ReentrantLock();

	/**
	 * Condition to coordinate closing thread and "last" task that issues "isEmpty" event.
	 */
	private static Condition isEmpty = closeLock.newCondition();

	/**
	 * Lifecycle of graceful shutdown.
	 */
	private static final int RUNNING = 0;
	private static final int PREPARE = 1;
	private static final int SHUTDOWN = 2;

	/**
	 * Transfers the considered channel in "prepare-shudown" phase.
	 */
	private static volatile int state = RUNNING;

	/**
	 * {@link Closeable} that closes asynchronous channel group when queue is empty. You could place the
	 * {@link #closeGracefully()} method where ever you want.
	 * 
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public static void closeGracefully() {
		System.out.println("Starting graceful shutdown ...");
		closeLock.lock();
		try {
			state = PREPARE;
			GLOBAL_LOG_FILE_ACCESS.set(AsynchronousFileChannel.open(Paths.get(FILE_NAME),
					new HashSet<StandardOpenOption>(Arrays.asList(StandardOpenOption.DELETE_ON_CLOSE)), pool));
			System.out.println("Channel blocked for write access ...");
			if (!pool.getQueue().isEmpty()) {
				System.out.println("Waiting for signal that queue is empty ...");
				isEmpty.await();
				System.out.println("Received signal that queue is empty ... closing");
			} else {
				System.out.println("Don't have to wait, queue is empty ...");
			}
		} catch (InterruptedException e) {
			Thread.interrupted();
			System.out.println("Await was interrupted ... " + e.toString());
		} catch (Exception e) {
			System.out.println("Something unexpected happened ... " + e.toString());
		} finally {
			try {
				closeLock.unlock();
				GLOBAL_LOG_FILE.force(false);
				long size = Files.size(Paths.get(FILE_NAME));
				System.out.println("Expected file size (bytes): " + (fileindex.get() - 1) * 5);
				System.out.println("Actual file size (bytes): " + size);
				if (size == (fileindex.get() - 1) * 5)
					System.out.println("No write operation was lost!");
				GLOBAL_LOG_FILE.close(); // close the writable channel
				GLOBAL_LOG_FILE_ACCESS.get().close(); // close the read-only channel
				System.out.println("File closed ...");
				pool.shutdown(); // allow clean up tasks from previous close() operation to finish safely
				pool.awaitTermination(1, TimeUnit.MINUTES);
				System.out.println("Pool closed ...");
			} catch (Exception e) {
				System.out.println("Something unexpected happened ... " + e.toString());
			}
		}
	}

	/**
	 * Custom {@link ThreadPoolExecutor} that supports graceful closing of asynchronous I/O channels.
	 */
	private static class DefensiveThreadPoolExecutor extends ThreadPoolExecutor {

		public DefensiveThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
				BlockingQueue<Runnable> workQueue) {
			super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
		}

		/**
		 * "Last" task issues a signal that queue is empty after task processing was completed.
		 */
		@Override
		protected void afterExecute(Runnable r, Throwable t) {
			if (state == PREPARE) {
				closeLock.lock(); // only one thread will pass when closer thread is awaiting signal
				try {
					if (pool.getQueue().isEmpty() && state < SHUTDOWN) {
						System.out.println("Issueing signal that queue is empty ...");
						isEmpty.signal();
						state = SHUTDOWN; // -> no other thread issue empty-signal
					}
				} finally {
					closeLock.unlock();
				}
			}
			super.afterExecute(r, t);
		}
	}

}
