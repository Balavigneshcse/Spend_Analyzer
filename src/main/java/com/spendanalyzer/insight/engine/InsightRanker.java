package com.spendanalyzer.insight.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.insight.engine.Finding.TipDraft;

/**
 * Chooses what the user sees: the most important findings first, at most six, at most two of one
 * type, and one piece of good news whenever there is any.
 */
final class InsightRanker {

	static final int MAX_INSIGHTS = 6;

	static final int MAX_PER_TYPE = 2;

	static final int MAX_TIPS = 3;

	/** Priority first; type and id only make the order stable. */
	private static final Comparator<Finding> ORDER = Comparator.comparingDouble(Finding::priority)
		.reversed()
		.thenComparing(Finding::type)
		.thenComparing(Finding::id);

	private InsightRanker() {
	}

	static List<Finding> rank(List<Finding> findings) {
		List<Finding> ranked = new ArrayList<>(findings);
		ranked.sort(ORDER);
		return ranked;
	}

	/** @param ranked the findings, most important first */
	static List<Finding> select(List<Finding> ranked) {
		List<Finding> picked = new ArrayList<>();
		Map<InsightType, Integer> perType = new EnumMap<>(InsightType.class);
		for (Finding finding : ranked) {
			if (picked.size() == MAX_INSIGHTS) {
				break;
			}
			if (perType.getOrDefault(finding.type(), 0) < MAX_PER_TYPE) {
				picked.add(finding);
				perType.merge(finding.type(), 1, Integer::sum);
			}
		}
		if (picked.stream().noneMatch(Finding::positive)) {
			ranked.stream().filter(Finding::positive).findFirst().ifPresent(good -> makeRoom(picked, perType, good));
		}
		return picked;
	}

	/** Puts the best good news last, dropping the least important finding if the list is full. */
	private static void makeRoom(List<Finding> picked, Map<InsightType, Integer> perType, Finding good) {
		boolean typeFull = perType.getOrDefault(good.type(), 0) >= MAX_PER_TYPE;
		if (typeFull) {
			// drop the weaker finding of the same type so the type limit still holds
			for (int i = picked.size() - 1; i >= 0; i--) {
				if (picked.get(i).type() == good.type()) {
					picked.remove(i);
					break;
				}
			}
		}
		else if (picked.size() == MAX_INSIGHTS) {
			picked.remove(picked.size() - 1);
		}
		picked.add(good);
	}

	/** Tips of the shown findings first, then of the others, in order of importance. */
	static List<TipDraft> tips(List<Finding> shown, List<Finding> ranked) {
		List<TipDraft> tips = new ArrayList<>();
		List<Finding> order = new ArrayList<>(shown);
		for (Finding finding : ranked) {
			if (!shown.contains(finding)) {
				order.add(finding);
			}
		}
		for (Finding finding : order) {
			if (finding.tip() != null && tips.size() < MAX_TIPS) {
				tips.add(finding.tip());
			}
		}
		return tips;
	}

}
