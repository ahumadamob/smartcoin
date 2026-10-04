package com.smartcoin.shared.validation;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.validation.ConstraintValidatorContext;

import com.smartcoin.shared.config.AppProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class PasswordPolicyValidatorTest {

	private final PasswordPolicyValidator validator = new PasswordPolicyValidator(new AppProperties(
			ZoneId.of("America/Argentina/Mendoza"), new AppProperties.Budget(24, 10),
			new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10)));

	private final ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

	@ParameterizedTest
	@CsvSource({ "9,false", "10,true", "11,true", "72,true", "73,false" })
	void lengthBoundariesInAsciiCharacters(int length, boolean valid) {
		assertThat(validator.isValid("a".repeat(length), context)).isEqualTo(valid);
	}

	@Test
	void maximumIsMeasuredInBytesNotCharacters() {
		// 'ñ' ocupa 2 bytes: 36 caracteres son 72 bytes, 37 son 74.
		assertThat(validator.isValid("ñ".repeat(36), context)).isTrue();
		assertThat(validator.isValid("ñ".repeat(37), context)).isFalse();
	}

	@Test
	void nullIsLeftToNotBlank() {
		assertThat(validator.isValid(null, context)).isTrue();
	}
}
