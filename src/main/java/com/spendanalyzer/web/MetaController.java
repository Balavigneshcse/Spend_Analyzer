package com.spendanalyzer.web;

import java.util.Arrays;
import java.util.List;

import com.spendanalyzer.model.AccountType;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.PaymentMode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed lists the dashboard needs to draw its forms: categories, payment modes, accounts. */
@RestController
public class MetaController {

	public record CategoryInfo(String code, String label, String need) {
	}

	public record ModeInfo(String code, String label, String account) {
	}

	public record AccountInfo(String code, String label) {
	}

	public record Meta(List<CategoryInfo> categories, List<ModeInfo> paymentModes, List<AccountInfo> accounts) {
	}

	private static final Meta META = new Meta(
			Arrays.stream(Category.values())
				.map(c -> new CategoryInfo(c.name(), c.label(), c.needFlag().name()))
				.toList(),
			Arrays.stream(PaymentMode.values())
				.map(m -> new ModeInfo(m.name(), m.label(), m.account().name()))
				.toList(),
			Arrays.stream(AccountType.values()).map(a -> new AccountInfo(a.name(), a.label())).toList());

	@GetMapping("/api/meta")
	public Meta meta() {
		return META;
	}

}
