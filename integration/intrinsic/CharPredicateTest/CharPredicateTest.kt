package CharPredicateTest

// Exercises the ASCII Char predicates added to ktc/Chars.kt.
// main returns a non-zero exit code if any check fails (the harness treats that as a failure).

fun main(): Int {
	var vOk = true

	// isDigit
	vOk = vOk && '0'.isDigit() && '9'.isDigit() && !'a'.isDigit() && !' '.isDigit()

	// isLetter / isLetterOrDigit
	vOk = vOk && 'a'.isLetter() && 'z'.isLetter() && 'A'.isLetter() && 'Z'.isLetter()
	vOk = vOk && !'5'.isLetter() && !'-'.isLetter()
	vOk = vOk && 'q'.isLetterOrDigit() && '4'.isLetterOrDigit() && !'#'.isLetterOrDigit()

	// isWhitespace
	vOk = vOk && ' '.isWhitespace() && '\t'.isWhitespace() && '\n'.isWhitespace() && '\r'.isWhitespace()
	vOk = vOk && !'x'.isWhitespace()

	// case predicates + conversions
	vOk = vOk && 'A'.isUpperCase() && !'a'.isUpperCase()
	vOk = vOk && 'a'.isLowerCase() && !'A'.isLowerCase()
	vOk = vOk && ('a'.uppercaseChar() == 'A') && ('!'.uppercaseChar() == '!')
	vOk = vOk && ('Z'.lowercaseChar() == 'z') && ('1'.lowercaseChar() == '1')

	// digitToInt
	vOk = vOk && ('7'.digitToInt() == 7) && ('0'.digitToInt() == 0)

	if (!vOk) {
		println("CharPredicateTest FAILED")
		return 1
	}
	println("CharPredicateTest OK")
	return 0
}
