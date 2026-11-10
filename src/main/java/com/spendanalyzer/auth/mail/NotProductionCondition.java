package com.spendanalyzer.auth.mail;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** True only outside production: no "prod" profile and not running on Render. */
public class NotProductionCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return !isProduction(context.getEnvironment());
	}

	/** Production = the "prod" profile is active, or the hosting platform's RENDER variable is set. */
	public static boolean isProduction(Environment environment) {
		String render = environment.getProperty("RENDER");
		return environment.acceptsProfiles(Profiles.of("prod")) || (render != null && !render.isBlank());
	}

}
