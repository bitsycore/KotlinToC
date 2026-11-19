import kotlinx.datetime.*
import kotlinx.datetime.format.*
import kotlin.time.Instant

// Dates and times without the clock: arithmetic, parsing and formats, and time zones (from the
// system's files on Linux and macOS, from the registry on Windows; the JVM has its own copy).

fun main() {
	val vDate = LocalDate(2026, 11, 15)
	println("$vDate ${vDate.dayOfWeek} ${vDate.dayOfYear}")
	println(vDate.plus(1, DateTimeUnit.MONTH))
	println(vDate.minus(DatePeriod(years = 1, days = 20)))
	println(LocalDate(2024, 1, 31).plus(1, DateTimeUnit.MONTH))
	println(vDate.daysUntil(LocalDate(2027, 1, 1)))
	println(LocalDateTime(2024, 2, 29, 13, 45, 30).toString())
	println(LocalDate.parse("2023-12-31").plus(DatePeriod(days = 1)))
	println(LocalTime.parse("23:59:59.5").toSecondOfDay())
	println(DateTimePeriod.parse("P1Y2M3DT4H5M6S"))
	println(YearMonth(2024, 2).numberOfDays)
	val vFormat = LocalDate.Format {
		day(Padding.ZERO); char('/'); monthNumber(); char('/'); year()
	}
	println(vDate.format(vFormat))
	println(vFormat.parse("01/02/2003"))
	println(LocalDate.Formats.ISO_BASIC.format(vDate))
	val vInstant = Instant.fromEpochSeconds(1_700_000_000)
	println(vInstant.toLocalDateTime(TimeZone.UTC))
	val vParis = TimeZone.of("Europe/Paris")
	println(vInstant.toLocalDateTime(vParis))
	println(vParis.offsetAt(vInstant))
	println(TimeZone.of("America/New_York").offsetAt(Instant.fromEpochSeconds(1_720_000_000)))
	println(LocalDateTime(2025, 3, 30, 2, 30).toInstant(vParis))
	println(UtcOffset(hours = 5, minutes = 30))
	println(FixedOffsetTimeZone(UtcOffset(hours = -3)).offsetAt(vInstant))
}
