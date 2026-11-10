package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.spendanalyzer.insight.engine.AnomalyDetector.Anomaly;
import com.spendanalyzer.insight.engine.Finding.TipDraft;
import com.spendanalyzer.insight.engine.Tally.Bucket;
import com.spendanalyzer.insight.engine.Tally.ItemGroup;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.NeedFlag;

/**
 * Detectors that describe the chosen period: small leaks, unusual spends, change against the
 * previous period, needs against wants, the top category, categories to check, no-spend days.
 * Each one first checks its rule in {@link Guardrails} and stays silent below it.
 */
final class PeriodFindings {

	private PeriodFindings() {
	}

	static void addAll(Picture p, List<Finding> out) {
		leaks(p, out);
		unusual(p, out);
		overallChange(p, out);
		categoryMover(p, out);
		needsWants(p, out);
		topCategory(p, out);
		reviewNeeded(p, out);
		noSpendDays(p, out);
	}

	// ------------------------------------------------------------------ small leaks

	private static void leaks(Picture p, List<Finding> out) {
		Tally now = p.now();
		for (ItemGroup leak : LeakDetector.detect(now.rows(), true)) {
			Facts facts = Facts.of()
				.text("item", leak.key())
				.text("Item", Wording.capitalise(leak.key()))
				.count("count", leak.count())
				.rupees("total", leak.total())
				.text("when", p.words().when());
			boolean early = p.endsToday() && Guardrails.nearMinimum(leak.count(), Guardrails.LEAK_MIN_COUNT);
			String text = early ? "You have bought {item} {count} times {when} so far: {total} in total."
					: p.words()
						.pick("leak", "You bought {item} {count} times {when}: {total} in total.",
								"{Item} came up {count} times {when} and cost {total} in total.");
			Severity severity = Num.ratio(leak.total(), now.total()) >= 0.10 ? Severity.WARN : Severity.INFO;
			out.add(Finding.of("leak-" + Wording.slug(leak.key()), InsightType.SMALL_LEAK, severity, "{Item} adds up",
					text, facts, Finding.impact(leak.total(), now.total()),
					Finding.confidence(leak.count(), Guardrails.LEAK_MIN_COUNT), leakTip(p, leak)));
		}
	}

	/** "Cap juice at 3 a week": half the current weekly rate, with the saving it gives per month. */
	private static TipDraft leakTip(Picture p, ItemGroup leak) {
		BigDecimal price = leak.typicalPrice();
		// the rate per week comes from the days tracked, not from the whole calendar period
		int days = p.trackedDays();
		if (days < 14) {
			// Too short a period to talk about "a month": say what half as many would have saved.
			BigDecimal saving = Num.wholeRupees(price.multiply(BigDecimal.valueOf(leak.count() / 2)));
			Facts facts = Facts.of().text("item", leak.key()).rupees("saving", saving).text("when", p.words().when());
			return TipDraft.of("Buying {item} half as often would have saved about {saving} {when}.", saving, facts);
		}
		double perWeek = leak.count() * 7.0 / days;
		long cap = Math.max(1, (long) Math.floor(perWeek / 2));
		BigDecimal saving = Num.nearestTen(Num.money((perWeek - cap) * price.doubleValue() * 30 / 7));
		if (saving.signum() <= 0) {
			return null;
		}
		Facts facts = Facts.of().text("item", leak.key()).count("cap", cap).rupees("saving", saving);
		return TipDraft.of("Cap {item} at {cap} a week: saves about {saving} a month.", saving, facts);
	}

	// ------------------------------------------------------------------ unusual spends

	private static void unusual(Picture p, List<Finding> out) {
		Set<String> ids = new HashSet<>();
		for (Anomaly anomaly : p.anomalies().anomalies()) { // largest first
			Expense spend = anomaly.expense();
			if (!p.period().contains(spend.date())) {
				continue;
			}
			if (spend.amount().subtract(anomaly.typical()).compareTo(Guardrails.ANOMALY_MIN_EXTRA) < 0) {
				continue; // unusual on paper, but too few rupees to be worth a sentence
			}
			String id = "unusual-" + spend.date() + "-" + Wording.slug(spend.itemKey());
			if (!ids.add(id)) {
				continue;
			}
			Facts facts = Facts.of()
				.rupees("amount", spend.amount())
				.text("what", spend.label())
				.date("date", spend.date())
				.times("times", anomaly.ratio())
				.text("category", spend.category().label())
				.rupees("typical", anomaly.typical());
			Severity severity = Num.ratio(spend.amount(), p.now().total()) >= 0.20 ? Severity.WARN : Severity.INFO;
			String text = anomaly.withinCategory()
					? "{amount} on {what} on {date} is about {times} times your usual {category} spend of {typical}."
					: "{amount} on {what} on {date} stands out: about {times} times your typical spend of {typical}.";
			out.add(Finding.of(id, InsightType.UNUSUAL_SPEND, severity, "Unusually large spend", text, facts,
					Finding.impact(spend.amount(), p.now().total()), 1, null));
			if (ids.size() == 2) {
				break;
			}
		}
	}

	// ------------------------------------------------------------------ period over period

	private static void overallChange(Picture p, List<Finding> out) {
		Comparison comparison = p.comparison();
		if (!comparison.speakable()) {
			return;
		}
		Tally now = p.now();
		Tally before = p.before();
		BigDecimal delta = now.total().subtract(before.total());
		double percent = comparison.changePercent();
		if (Math.abs(percent) < Guardrails.CHANGE_MIN_PERCENT
				|| delta.abs().compareTo(Guardrails.CHANGE_MIN_RUPEES) < 0) {
			return;
		}
		if (delta.signum() < 0 && p.tooEarlyForDown()) {
			return; // an unfinished today against a full day: "down" would be premature
		}
		if (!holdsWithoutBills(p, delta)) {
			return;
		}
		Facts facts = Facts.of()
			.percent("pct", percent)
			.rupees("now", now.total())
			.rupees("before", before.total())
			.text("previous", p.words().previous());
		double impact = Finding.impact(delta, now.total());
		double confidence = Finding.confidence(Math.min(now.count(), before.count()), Guardrails.MIN_EXPENSES);
		// two small samples: say it as a first sign, not as a settled fact
		boolean early = p.endsToday()
				&& Guardrails.nearMinimum(Math.min(now.count(), before.count()), Guardrails.MIN_EXPENSES);
		if (delta.signum() > 0) {
			String text = early ? "Early sign: so far you have spent {pct} more than in {previous}, {now} against {before}."
					: p.words()
						.pick("up", "You spent {pct} more than in {previous}: {now} against {before}.",
								"Spending is up {pct} on {previous}: {now} now, {before} then.");
			out.add(Finding.of("change-overall", InsightType.PERIOD_CHANGE,
					percent >= 30 ? Severity.WARN : Severity.INFO, "Spending is up", text, facts, impact, confidence,
					null));
		}
		else {
			String text = early ? "Early sign: so far you have spent {pct} less than in {previous}, {now} against {before}."
					: p.words()
						.pick("down", "You spent {pct} less than in {previous}: {now} against {before}.",
								"Spending is down {pct} on {previous}: {now} now, {before} then.");
			out.add(Finding.of("positive-spending-down", InsightType.POSITIVE, Severity.GOOD, "Spending is down", text,
					facts, impact, confidence, null));
		}
	}

	/**
	 * A recurring bill that fell inside one window and outside the other (rent paid on the 3rd last
	 * month, not yet this month) can make the whole change on its own. The totals stay as they
	 * are, but the change is only worth a sentence if it is still there, in the same direction,
	 * once the known bills are left out of both windows.
	 */
	private static boolean holdsWithoutBills(Picture p, BigDecimal delta) {
		if (p.recurring().isEmpty()) {
			return true;
		}
		BigDecimal now = withoutBills(p.now(), p);
		BigDecimal before = withoutBills(p.before(), p);
		BigDecimal rest = now.subtract(before);
		if (rest.signum() != delta.signum() || rest.abs().compareTo(Guardrails.CHANGE_MIN_RUPEES) < 0) {
			return false;
		}
		// nothing but bills before: there is no everyday base to take a percentage of
		return before.signum() > 0 && Math.abs(Num.changePercent(now, before)) >= Guardrails.CHANGE_MIN_PERCENT;
	}

	private static BigDecimal withoutBills(Tally tally, Picture p) {
		BigDecimal sum = BigDecimal.ZERO;
		for (Expense row : tally.rows()) {
			if (!BurnModel.isBill(row, p.recurring())) {
				sum = sum.add(row.amount());
			}
		}
		return sum;
	}

	/** Categories that hold a recurring bill in either window: its pay date, not a habit, moves them. */
	private static Set<Category> billCategories(Picture p) {
		Set<Category> billed = EnumSet.noneOf(Category.class);
		for (Tally tally : List.of(p.now(), p.before())) {
			for (Expense row : tally.rows()) {
				if (BurnModel.isBill(row, p.recurring())) {
					billed.add(row.category());
				}
			}
		}
		return billed;
	}

	/**
	 * The category whose spending moved most, in rupees, against the previous period. Categories
	 * with a recurring bill are not compared, and early in a period to date only a rise counts.
	 */
	private static void categoryMover(Picture p, List<Finding> out) {
		Comparison comparison = p.comparison();
		Tally now = p.now();
		Tally before = p.before();
		// Same sample rule as the overall comparison, but the overall base may be small.
		if (!comparison.tracked() || now.count() < Guardrails.MIN_EXPENSES
				|| before.count() < Guardrails.MIN_EXPENSES) {
			return;
		}
		Category mover = null;
		BigDecimal delta = BigDecimal.ZERO;
		Set<Category> billed = billCategories(p);
		boolean risesOnly = p.tooEarlyForDown();
		for (Category category : Category.values()) {
			if (billed.contains(category)) {
				continue;
			}
			BigDecimal change = now.category(category).amount().subtract(before.category(category).amount());
			if (risesOnly && change.signum() < 0) {
				continue; // an unfinished today against a full day: "down" would be premature
			}
			if (change.abs().compareTo(delta.abs()) > 0) {
				mover = category;
				delta = change;
			}
		}
		if (mover == null || delta.abs().compareTo(Guardrails.CHANGE_MIN_RUPEES) < 0) {
			return;
		}
		Bucket current = now.category(mover);
		Bucket previous = before.category(mover);
		boolean percentOk = Guardrails.percentAllowed(previous.amount(), previous.count());
		double percent = percentOk ? Num.changePercent(current.amount(), previous.amount()) : 0;
		if (percentOk && Math.abs(percent) < Guardrails.CHANGE_MIN_PERCENT) {
			return;
		}
		boolean up = delta.signum() > 0;
		Facts facts = Facts.of()
			.text("category", mover.label())
			.rupees("now", current.amount())
			.rupees("before", previous.amount())
			.rupees("delta", delta.abs())
			.percent("pct", percent)
			.text("previous", p.words().previous())
			.text("when", p.words().when());
		String text;
		if (percentOk) {
			text = up ? "{category} is up {pct} on {previous}: {now} against {before}."
					: "{category} is down {pct} on {previous}: {now} against {before}.";
		}
		else if (previous.amount().signum() == 0) {
			text = "You spent {now} on {category} {when}, and nothing on it in {previous}.";
		}
		else if (current.amount().signum() == 0) {
			text = "You spent nothing on {category} {when}, against {before} in {previous}.";
		}
		else {
			// too small a base for a percentage: rupees only
			text = up ? "{category} is {delta} higher than in {previous}: {now} against {before}."
					: "{category} is {delta} lower than in {previous}: {now} against {before}.";
		}
		// Less spent on a need is not good news by itself: most likely it is not paid or not logged yet.
		Severity severity = mover.needFlag() == NeedFlag.NEED ? Severity.INFO : Severity.GOOD;
		if (up) {
			boolean bigWant = mover.needFlag() == NeedFlag.WANT && Num.ratio(delta, now.total()) >= 0.10;
			severity = bigWant ? Severity.WARN : Severity.INFO;
		}
		out.add(Finding.of("change-" + Wording.slug(mover.name()), InsightType.PERIOD_CHANGE, severity,
				up ? "{category} is up" : "{category} is down", text, facts, Finding.impact(delta, now.total()),
				Finding.confidence(Math.min(now.count(), before.count()), Guardrails.MIN_EXPENSES), null));
	}

	// ------------------------------------------------------------------ needs against wants

	private static void needsWants(Picture p, List<Finding> out) {
		Tally now = p.now();
		Bucket needs = now.flagged(NeedFlag.NEED);
		Bucket wants = now.flagged(NeedFlag.WANT);
		BigDecimal base = needs.amount().add(wants.amount());
		int count = needs.count() + wants.count();
		if (!Guardrails.percentAllowed(base, count)) {
			return;
		}
		if (Num.ratio(now.needsReview().amount(), now.total()) >= 0.25) {
			return; // too many unchecked categories: the split cannot be trusted yet
		}
		double share = Num.percentOf(wants.amount(), base);
		Facts facts = Facts.of()
			.percent("pct", share)
			.rupees("wants", wants.amount())
			.rupees("needs", needs.amount())
			.text("when", p.words().when())
			// transfers and unknown spends are outside the split, so be exact about the base
			.text("base", base.compareTo(now.total()) == 0 ? "your spending" : "your spending on needs and wants")
			.text("start", Guardrails.nearMinimum(count, Guardrails.MIN_EXPENSES) ? "So far wants" : "Wants");
		double confidence = Finding.confidence(count, Guardrails.MIN_EXPENSES);
		if (share >= 40) {
			BigDecimal saving = Num.nearestTen(wants.amount().subtract(base.multiply(new BigDecimal("0.30"))));
			Facts tipFacts = Facts.of().rupees("saving", saving).text("when", p.words().when());
			TipDraft tip = TipDraft.of("Keeping wants to 30% of spending would have freed about {saving} {when}.",
					saving, tipFacts);
			out.add(Finding.of("needs-wants", InsightType.NEEDS_WANTS, share >= 50 ? Severity.WARN : Severity.INFO,
					share >= 50 ? "Wants outweigh needs" : "Wants are a big share",
					"{start} make up {pct} of {base} {when}: {wants} on wants against {needs} on needs.", facts,
					Finding.impact(saving, now.total()), confidence, tip));
		}
		else if (share <= 30) {
			String text = wants.amount().signum() == 0 ? "All of {base} {when} went on needs: {needs}."
					: "Only {pct} of {base} {when} went on wants: {wants}, against {needs} on needs.";
			out.add(Finding.of("needs-wants", InsightType.NEEDS_WANTS, Severity.GOOD, "Needs come first", text, facts,
					0.5, confidence, null));
		}
	}

	// ------------------------------------------------------------------ top category

	private static void topCategory(Picture p, List<Finding> out) {
		Tally now = p.now();
		if (!Guardrails.percentAllowed(now.total(), now.count()) || now.byCategory().size() < 2) {
			return;
		}
		var top = now.categoriesBySize().get(0);
		Category category = top.getKey();
		Bucket bucket = top.getValue();
		double share = Num.percentOf(bucket.amount(), now.total());
		Facts facts = Facts.of()
			.text("category", category.label())
			.rupees("amount", bucket.amount())
			.count("count", bucket.count())
			.text("purchases", Wording.plural(bucket.count(), "purchase", "purchases"))
			.percent("pct", share)
			.text("when", p.words().when());
		Severity severity = category.needFlag() == NeedFlag.WANT && share >= 40 ? Severity.WARN : Severity.INFO;
		String text = p.words()
			.pick("top", "{category} is your biggest category {when}: {amount} over {count} {purchases}, {pct} of your spending.",
					"The largest share of your money {when} went on {category}: {amount} over {count} {purchases}, {pct} of your spending.");
		out.add(Finding.of("top-category", InsightType.TOP_CATEGORY, severity, "{category} leads your spending", text,
				facts, share / 100, Finding.confidence(now.count(), Guardrails.MIN_EXPENSES), null));
	}

	// ------------------------------------------------------------------ categories to check

	private static void reviewNeeded(Picture p, List<Finding> out) {
		Tally now = p.now();
		Bucket review = now.needsReview();
		if (review.count() == 0) {
			return;
		}
		Facts facts = Facts.of()
			.count("count", review.count())
			.rupees("amount", review.amount())
			.text("spends", Wording.plural(review.count(), "spend", "spends"))
			.text("when", p.words().when());
		String text = review.count() == 1
				? "1 spend {when} ({amount}) has an uncertain category. Checking it keeps this analysis accurate."
				: "{count} spends {when} ({amount} in total) have an uncertain category. Checking them keeps this analysis accurate.";
		double share = Num.ratio(review.amount(), now.total());
		out.add(Finding.of("review-needed", InsightType.REVIEW_NEEDED, share >= 0.25 ? Severity.WARN : Severity.INFO,
				"{count} {spends} to check", text, facts, share / 0.25, 1, null));
	}

	// ------------------------------------------------------------------ no-spend days

	private static void noSpendDays(Picture p, List<Finding> out) {
		Tally now = p.now();
		PeriodWindow period = p.period();
		LocalDate since = p.in().trackingSince();
		// Only count quiet days once tracking had started, or "no spending" just means "no notebook".
		if (now.count() < Guardrails.MIN_EXPENSES || since == null || since.isAfter(period.from())) {
			return;
		}
		int days = period.days();
		int quiet = days - now.byDay().size();
		if (days < 7 || quiet < 2 || quiet * 2 > days) {
			return;
		}
		Facts facts = Facts.of().count("quiet", quiet).count("days", days);
		out.add(Finding.of("positive-no-spend-days", InsightType.POSITIVE, Severity.GOOD, "No-spend days",
				"{quiet} of " + p.words().daysSpan() + " had no spending recorded.", facts, 0.3,
				Finding.confidence(days, 7), null));
	}

}
