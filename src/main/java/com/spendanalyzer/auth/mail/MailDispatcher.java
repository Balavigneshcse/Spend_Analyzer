package com.spendanalyzer.auth.mail;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sends mails on two background threads, after the request's database work has committed.
 * A slow mail provider therefore never holds a request thread, and how long a request takes says
 * nothing about whether a mail went out. The queue is bounded: when it is full the mail is dropped
 * and logged (the user can ask for a new code) instead of piling up in memory.
 */
@Component
public class MailDispatcher {

	private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

	private static final int THREADS = 2;

	private static final int QUEUE_SIZE = 100;

	private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

	private final EmailSender sender;

	private final ThreadPoolExecutor executor;

	private final AtomicInteger unfinished = new AtomicInteger();

	public MailDispatcher(ObjectProvider<EmailSender> sender, MailStartupCheck checked) {
		this.sender = sender.getIfAvailable(() -> {
			throw new IllegalStateException("No email sender: set app.mail.provider to \"brevo\" (production) "
					+ "or \"console\" (local development only)");
		});
		AtomicInteger number = new AtomicInteger();
		this.executor = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(QUEUE_SIZE), runnable -> {
					Thread thread = new Thread(runnable, "mail-" + number.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				});
		// no idle threads on a tiny host
		this.executor.allowCoreThreadTimeOut(true);
	}

	/** Queues a mail and returns at once. {@code null} means "nothing to send". */
	public void send(EmailMessage message) {
		if (message == null) {
			return;
		}
		unfinished.incrementAndGet();
		try {
			executor.execute(() -> deliver(message));
		}
		catch (RejectedExecutionException ex) {
			unfinished.decrementAndGet();
			log.error("Mail queue is full or closed: dropped {} mail to {}", message.kind(), message.maskedTo());
		}
	}

	/** Waits until every queued mail has been handled. For tests and shutdown. */
	public boolean awaitIdle(Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (unfinished.get() > 0) {
			if (System.nanoTime() > deadline) {
				return false;
			}
			try {
				Thread.sleep(5);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}

	private void deliver(EmailMessage message) {
		try {
			sender.send(message);
		}
		catch (EmailException ex) {
			log.error("Could not send {} mail to {}: {}", message.kind(), message.maskedTo(), ex.getMessage());
		}
		catch (RuntimeException ex) {
			// only the exception type: an unknown exception text could contain the mail
			log.error("Could not send {} mail to {}: {}", message.kind(), message.maskedTo(), ex.getClass().getName());
		}
		finally {
			unfinished.decrementAndGet();
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(SHUTDOWN_WAIT.toSeconds(), TimeUnit.SECONDS)) {
				int dropped = executor.shutdownNow().size();
				log.warn("Mail sender stopped with {} mails unsent", dropped);
			}
		}
		catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

}
