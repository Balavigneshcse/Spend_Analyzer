package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/** Mails leave on background threads; a slow or broken provider never reaches the caller. */
class MailDispatcherTest {

	private static final EmailMessage MAIL = MailTemplates.registerCode("student@example.com", "493817", 10);

	private static MailStartupCheck passedCheck() {
		return new MailStartupCheck(new MockEnvironment().withProperty("app.security.otp-pepper", "test-pepper"));
	}

	private static MailDispatcher dispatcherFor(EmailSender sender) {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		if (sender != null) {
			beans.addBean("emailSender", sender);
		}
		return new MailDispatcher(beans.getBeanProvider(EmailSender.class), passedCheck());
	}

	@Test
	void sendReturnsAtOnceEvenWhenTheProviderIsSlow() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		List<EmailMessage> delivered = new CopyOnWriteArrayList<>();
		MailDispatcher dispatcher = dispatcherFor(message -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			delivered.add(message);
		});
		try {
			long start = System.nanoTime();
			dispatcher.send(MAIL);
			dispatcher.send(null);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
			assertThat(delivered).isEmpty();
			assertThat(dispatcher.awaitIdle(Duration.ofMillis(50))).isFalse();

			release.countDown();
			assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
			assertThat(delivered).containsExactly(MAIL);
		}
		finally {
			release.countDown();
			dispatcher.shutdown();
		}
	}

	@Test
	void aFailingProviderDoesNotStopLaterMails() {
		List<EmailMessage> delivered = new CopyOnWriteArrayList<>();
		MailDispatcher dispatcher = dispatcherFor(message -> {
			if (delivered.isEmpty()) {
				delivered.add(message);
				throw new EmailException("Brevo answered HTTP 500: down");
			}
			if (delivered.size() == 1) {
				delivered.add(message);
				throw new IllegalStateException("unexpected");
			}
			delivered.add(message);
		});
		try {
			for (int i = 0; i < 3; i++) {
				dispatcher.send(MAIL);
				assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
			}
			assertThat(delivered).hasSize(3);
		}
		finally {
			dispatcher.shutdown();
		}
	}

	@Test
	void aFullQueueDropsMailsInsteadOfBlockingTheCaller() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		List<EmailMessage> delivered = new CopyOnWriteArrayList<>();
		MailDispatcher dispatcher = dispatcherFor(message -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			delivered.add(message);
		});
		try {
			long start = System.nanoTime();
			for (int i = 0; i < 500; i++) {
				dispatcher.send(MAIL);
			}
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
			release.countDown();
			assertThat(dispatcher.awaitIdle(Duration.ofSeconds(10))).isTrue();
			// two in flight plus a queue of one hundred; the rest were dropped (and logged)
			assertThat(delivered).hasSize(102);
		}
		finally {
			release.countDown();
			dispatcher.shutdown();
		}
	}

	@Test
	void afterShutdownNothingIsSent() {
		List<EmailMessage> delivered = new CopyOnWriteArrayList<>();
		MailDispatcher dispatcher = dispatcherFor(delivered::add);
		dispatcher.send(MAIL);
		dispatcher.shutdown();
		assertThat(delivered).containsExactly(MAIL);

		dispatcher.send(MAIL);
		assertThat(dispatcher.awaitIdle(Duration.ofSeconds(1))).isTrue();
		assertThat(delivered).hasSize(1);
	}

	@Test
	void withoutASenderTheApplicationDoesNotStart() {
		assertThatThrownBy(() -> dispatcherFor(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.mail.provider");
	}

}
