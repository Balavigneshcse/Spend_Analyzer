package com.spendanalyzer.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Put this on a test class to get the whole application with MockMvc, the "test" profile and a
 * freshly migrated test database. All test classes share one application context, so the database
 * is wiped once per test run, not per class: create your own users per test (see {@link TestData}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
// The administrator list of every integration test (written with odd case and spaces on purpose).
// It is here, not in a test class, so that all test classes keep sharing one application context.
@SpringBootTest(properties = "app.admin.emails=" + IntegrationTest.ADMIN_EMAILS)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfig.class)
public @interface IntegrationTest {

	/** As an owner might type it: mixed case, spaces and an empty item. */
	String ADMIN_EMAILS = "  Stats.Admin@Example.COM , ,second.admin@example.com  ";

	/** The first administrator above, as its login email is stored. */
	String ADMIN_EMAIL = "stats.admin@example.com";

}
