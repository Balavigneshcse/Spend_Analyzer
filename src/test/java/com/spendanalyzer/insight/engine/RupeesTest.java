package com.spendanalyzer.insight.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class RupeesTest {

	private static String format(String amount) {
		return Rupees.format(new BigDecimal(amount));
	}

	@Test
	void groupsDigitsTheIndianWay() {
		assertThat(format("0")).isEqualTo("₹0");
		assertThat(format("560")).isEqualTo("₹560");
		assertThat(format("999.00")).isEqualTo("₹999");
		assertThat(format("1000")).isEqualTo("₹1,000");
		assertThat(format("1250")).isEqualTo("₹1,250");
		assertThat(format("12345")).isEqualTo("₹12,345");
		assertThat(format("123456")).isEqualTo("₹1,23,456");
		assertThat(format("1234567")).isEqualTo("₹12,34,567");
		assertThat(format("10000000")).isEqualTo("₹1,00,00,000");
	}

	@Test
	void showsPaiseOnlyWhenThereAreAny() {
		assertThat(format("107.50")).isEqualTo("₹107.50");
		assertThat(format("107.5")).isEqualTo("₹107.50");
		assertThat(format("1234567.05")).isEqualTo("₹12,34,567.05");
		assertThat(format("40.00")).isEqualTo("₹40");
		assertThat(format("0.999")).isEqualTo("₹1");
	}

	@Test
	void negativeAmountsKeepTheirSign() {
		assertThat(format("-1250")).isEqualTo("-₹1,250");
	}

	@Test
	void templatesOnlyPrintComputedFacts() {
		Facts facts = Facts.of()
			.rupees("total", new BigDecimal("560.00"))
			.about("perDay", new BigDecimal("452.38"))
			.count("count", 14)
			.percent("share", 33.26)
			.times("times", 2.54)
			.date("until", LocalDate.parse("2026-11-30"))
			.text("item", "juice {total}");
		assertThat(facts.fill("{item}: {count} times, {total}, about {perDay} a day, {share}, {times}x, till {until}."))
			.isEqualTo("juice {total}: 14 times, ₹560, about ₹452 a day, 33%, 2.5x, till 30 Nov.");
		assertThat(facts.rupeeStrings()).containsExactly("₹560", "₹452");
		assertThatThrownBy(() -> facts.fill("You saved {saving}.")).isInstanceOf(IllegalStateException.class);
	}

}
