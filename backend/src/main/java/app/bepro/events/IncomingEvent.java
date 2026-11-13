package app.bepro.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One data point as sent by the phone.
 */
public record IncomingEvent(
		@NotBlank @Pattern(regexp = Categories.PATTERN) String category,
		@Size(max = 160) String subCategory,
		@NotNull @DecimalMin("-99999999") @DecimalMax("99999999") BigDecimal value,
		@NotBlank @Size(max = 16) String unit,
		@NotNull Instant startedAt,
		Instant endedAt,
		@NotBlank @Pattern(regexp = Sources.PATTERN) String source,
		Map<String, Object> metadata) {

	public String subCategoryOrEmpty() {
		return subCategory == null ? "" : subCategory;
	}

	public Instant endOrStart() {
		return endedAt == null ? startedAt : endedAt;
	}

}
