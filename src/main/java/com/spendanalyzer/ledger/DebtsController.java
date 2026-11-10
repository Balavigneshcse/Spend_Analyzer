package com.spendanalyzer.ledger;

import com.spendanalyzer.security.AuthUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/debts}: what the logged-in user owes each friend. Read-only; debts change by
 * adding or deleting BORROW and REPAY entries under {@code /api/entries}.
 */
@RestController
public class DebtsController {

	private final DebtReader debts;

	public DebtsController(DebtReader debts) {
		this.debts = debts;
	}

	@GetMapping("/api/debts")
	public DebtsView get(@AuthenticationPrincipal AuthUser user) {
		return debts.read(user.id());
	}

}
