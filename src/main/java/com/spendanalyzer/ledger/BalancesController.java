package com.spendanalyzer.ledger;

import com.spendanalyzer.security.AuthUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/balances}: the three balances of the logged-in user. */
@RestController
@RequestMapping("/api/balances")
public class BalancesController {

	private final BalanceService balances;

	private final EntryRequestParser parser;

	public BalancesController(BalanceService balances, EntryRequestParser parser) {
		this.balances = balances;
		this.parser = parser;
	}

	@GetMapping
	public BalancesView get(@AuthenticationPrincipal AuthUser user) {
		return balances.current(user.id());
	}

	@PutMapping("/opening")
	public BalancesView setOpening(@AuthenticationPrincipal AuthUser user, @RequestBody OpeningBalancesRequest body) {
		return balances.setOpening(user.id(), parser.parseOpening(body), parser.today());
	}

}
