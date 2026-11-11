package com.spendanalyzer.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Who is an administrator: the parsing of app.admin.emails. Plain JUnit. */
class AdminAccessTest {

	@Test
	void emptyOrMissingMeansNobody() {
		for (String configured : new String[] { null, "", "   ", ",", " , ,, " }) {
			AdminAccess access = new AdminAccess(configured);
			assertThat(access.count()).as("'%s'", configured).isZero();
			assertThat(access.isAdmin("someone@example.com")).isFalse();
			assertThat(access.isAdmin("")).isFalse();
			assertThat(access.isAdmin(null)).isFalse();
		}
	}

	@Test
	void caseAndSpacesDoNotMatter() {
		AdminAccess access = new AdminAccess("  Owner.Name@Example.COM ,\tsecond@example.com ");
		assertThat(access.count()).isEqualTo(2);
		assertThat(access.isAdmin("owner.name@example.com")).isTrue();
		assertThat(access.isAdmin("OWNER.NAME@example.com")).isTrue();
		assertThat(access.isAdmin("second@example.com")).isTrue();
	}

	@Test
	void blanksAreDroppedAndDuplicatesCountOnce() {
		assertThat(AdminAccess.parse("a@example.com,, ,A@EXAMPLE.COM,b@example.com,"))
			.containsExactlyInAnyOrder("a@example.com", "b@example.com");
	}

	@Test
	void onlyAnExactListedEmailIsAnAdministrator() {
		AdminAccess access = new AdminAccess("owner@example.com");
		assertThat(access.isAdmin("owner@example.co")).isFalse();
		assertThat(access.isAdmin("xowner@example.com")).isFalse();
		assertThat(access.isAdmin("owner@example.com.evil")).isFalse();
		assertThat(access.isAdmin("owner@example.com,other@example.com")).isFalse();
	}

	@Test
	void atMostTwentyEmailsAreKept() {
		String many = IntStream.rangeClosed(1, 25).mapToObj(i -> "a" + i + "@example.com").collect(Collectors.joining(","));
		AdminAccess access = new AdminAccess(many);
		assertThat(access.count()).isEqualTo(AdminAccess.MAX_EMAILS).isEqualTo(20);
		assertThat(access.isAdmin("a20@example.com")).isTrue();
		assertThat(access.isAdmin("a21@example.com")).isFalse();
	}

}
