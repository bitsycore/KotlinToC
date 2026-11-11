import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntPredicate;

/*
Writes ktc2/src/main/resources/ktc2/stdlib/Ktc2Unicode.kt: what the Char functions of the
platform layer need to know of Unicode, for the characters a Char can hold (U+0000..U+FFFF).

The tables are read from the JDK this runs on, so that ktc2 answers as that JDK does: run it
with the JDK the tests compare with (the toolchain of the build, JDK 21).

Usage (from ktc2/): <jdk 21>/bin/java tools/GenUnicode.java
*/
public class GenUnicode
{
	private static final int kLast = 0xFFFF;

	/* Four hexadecimal digits of [inValue]. */
	private static String hex(
		int inValue
		)
	{
		return String.format("%04X", inValue);
	}

	/* The ordinal of Kotlin's CharCategory for the type the JDK gives: the JDK skips the number 17. */
	private static int categoryOf(
		int inChar
		)
	{
		int vType = Character.getType(inChar);
		return vType < 17 ? vType : vType - 1;
	}

	/* The characters [inTest] accepts, as ranges: first and last of each, four digits each. */
	private static String ranges(
		IntPredicate inTest
		)
	{
		StringBuilder vOut   = new StringBuilder();
		int           vStart = -1;
		for (int vChar = 0; vChar <= kLast + 1; vChar++)
		{
			boolean vIsIn = vChar <= kLast && inTest.test(vChar);
			if (vIsIn && vStart < 0) vStart = vChar;
			if (!vIsIn && vStart >= 0)
			{
				vOut.append(hex(vStart)).append(hex(vChar - 1));
				vStart = -1;
			}
		}
		return vOut.toString();
	}

	/* A Kotlin string constant named [inName] holding [inText], cut into lines. */
	private static String constant(
		String inName,
		String inComment,
		String inText
		)
	{
		StringBuilder vOut = new StringBuilder();
		vOut.append("\n/** ").append(inComment).append(" */\n");
		vOut.append("private const val ").append(inName).append(" =\n");
		if (inText.isEmpty()) vOut.append("\t\"\"\n");
		for (int vAt = 0; vAt < inText.length(); vAt += 120)
		{
			int vEnd = Math.min(vAt + 120, inText.length());
			vOut.append("\t\"").append(inText, vAt, vEnd).append("\"").append(vEnd < inText.length() ? " +\n" : "\n");
		}
		return vOut.toString();
	}

	public static void main(
		String[] inArguments
		) throws IOException
	{
		// Runs of characters of one category: the first of each, and a letter for the category.
		StringBuilder vCategories = new StringBuilder();
		int           vPrevious   = -1;
		for (int vChar = 0; vChar <= kLast; vChar++)
		{
			int vCategory = categoryOf(vChar);
			if (vCategory != vPrevious) vCategories.append(hex(vChar)).append((char) ('a' + vCategory));
			vPrevious = vCategory;
		}

		// Single-character case mappings, where the character changes.
		StringBuilder vUpper = new StringBuilder();
		StringBuilder vLower = new StringBuilder();
		StringBuilder vTitle = new StringBuilder();
		// Characters whose uppercase form is more than one character, or is not the single-character mapping.
		StringBuilder vUpperStrings = new StringBuilder();
		StringBuilder vDigitZeros   = new StringBuilder();
		for (int vChar = 0; vChar <= kLast; vChar++)
		{
			if (Character.isSurrogate((char) vChar)) continue;
			int vToUpper = Character.toUpperCase(vChar);
			int vToLower = Character.toLowerCase(vChar);
			int vToTitle = Character.toTitleCase(vChar);
			if (vToUpper != vChar && vToUpper <= kLast) vUpper.append(hex(vChar)).append(hex(vToUpper));
			if (vToLower != vChar && vToLower <= kLast) vLower.append(hex(vChar)).append(hex(vToLower));
			if (vToTitle != vToUpper && vToTitle <= kLast) vTitle.append(hex(vChar)).append(hex(vToTitle));
			String vWhole = String.valueOf((char) vChar).toUpperCase(Locale.ROOT);
			if (!vWhole.equals(String.valueOf((char) (vToUpper <= kLast ? vToUpper : vChar))))
			{
				vUpperStrings.append(hex(vChar)).append(vWhole.length());
				for (int vAt = 0; vAt < vWhole.length(); vAt++) vUpperStrings.append(hex(vWhole.charAt(vAt)));
			}
			if (Character.getType(vChar) == Character.DECIMAL_DIGIT_NUMBER && Character.digit(vChar, 10) == 0) vDigitZeros.append(hex(vChar));
		}

		// Runs of characters of one script, and of one block: the first of each and, in two digits, which.
		StringBuilder vScripts    = new StringBuilder();
		StringBuilder vBlocks     = new StringBuilder();
		StringBuilder vBlockNames = new StringBuilder();
		java.util.ArrayList<Character.UnicodeBlock> vBlockList = new java.util.ArrayList<>();
		int vScript = -1;
		int vBlock  = -2;
		for (int vChar = 0; vChar <= kLast; vChar++)
		{
			int vNowScript = Character.UnicodeScript.of(vChar).ordinal();
			if (vNowScript != vScript) vScripts.append(hex(vChar)).append(String.format("%02X", vNowScript));
			vScript = vNowScript;
			Character.UnicodeBlock vIn = Character.UnicodeBlock.of(vChar);
			int vNowBlock = -1;
			if (vIn != null)
			{
				vNowBlock = vBlockList.indexOf(vIn);
				if (vNowBlock < 0)
				{
					vNowBlock = vBlockList.size();
					vBlockList.add(vIn);
					vBlockNames.append(vIn).append(',');
				}
			}
			if (vNowBlock != vBlock) vBlocks.append(hex(vChar)).append(String.format("%02X", vNowBlock < 0 ? 255 : vNowBlock));
			vBlock = vNowBlock;
		}
		StringBuilder vScriptNames = new StringBuilder();
		for (Character.UnicodeScript vEach : Character.UnicodeScript.values()) vScriptNames.append(vEach.name()).append(',');

		StringBuilder vOut = new StringBuilder();
		vOut.append("package kotlin.text\n\n");
		vOut.append("/*\nUnicode, for the characters a Char can hold: the general category of each, which are upper\n");
		vOut.append("and lower case, the case mappings, the digits. Generated by ktc2/tools/GenUnicode.java from the\n");
		vOut.append("JDK ").append(Runtime.version().feature()).append(" (Unicode ").append(unicodeVersion()).append("); the text of each table is decoded on first use.\n*/\n");
		vOut.append("\n// ==================\n// MARK: Tables\n// ==================\n");
		vOut.append(constant("kCategoryRuns", "Runs of one category: four digits for the first character, a character for the category (`a` + ordinal of CharCategory).", vCategories.toString()));
		vOut.append(constant("kUpperCaseRanges", "The characters `isUpperCase` accepts, as ranges: first and last, four digits each.", ranges(Character::isUpperCase)));
		vOut.append(constant("kLowerCaseRanges", "The characters `isLowerCase` accepts, as ranges.", ranges(Character::isLowerCase)));
		vOut.append(constant("kWhitespaceRanges", "The characters `isWhitespace` accepts, as ranges.", ranges(vChar -> Character.isWhitespace(vChar) || Character.isSpaceChar(vChar))));
		vOut.append(constant("kToUpperCase", "Each character whose uppercase form is another single character, with that form: four digits each.", vUpper.toString()));
		vOut.append(constant("kToLowerCase", "The same for lowercase.", vLower.toString()));
		vOut.append(constant("kToTitleCase", "The characters whose titlecase form is not their uppercase form, with that form.", vTitle.toString()));
		vOut.append(constant("kUpperCaseStrings", "The characters whose uppercase form in a string is several characters: the character, how many, and those.", vUpperStrings.toString()));
		vOut.append(constant("kDigitZeros", "The digit zero of each script: the nine characters after it are its other digits.", vDigitZeros.toString()));
		vOut.append(constant("kScriptRuns", "Runs of one script: four digits for the first character, two for the place of the script in `kScriptNames`.", vScripts.toString()));
		vOut.append(constant("kScriptNames", "The names of the scripts, each followed by a comma.", vScriptNames.toString()));
		vOut.append(constant("kBlockRuns", "Runs of one block: four digits for the first character, two for the place of the block in `kBlockNames` (FF: in no block).", vBlocks.toString()));
		vOut.append(constant("kBlockNames", "The names of the blocks, each followed by a comma.", vBlockNames.toString()));
		vOut.append(Files.readString(Path.of("tools/unicode_lookup.kt.txt"), StandardCharsets.UTF_8));
		Files.writeString(Path.of("src/main/resources/ktc2/stdlib/Ktc2Unicode.kt"), vOut.toString().replace("\r\n", "\n"), StandardCharsets.UTF_8);
		System.out.println("category runs: " + vCategories.length() / 5 + ", to upper: " + vUpper.length() / 8 + ", to lower: " + vLower.length() / 8
			+ ", to title: " + vTitle.length() / 8 + ", digit zeros: " + vDigitZeros.length() / 4);
	}

	/* The version of Unicode this JDK implements, by one character each version added. */
	private static String unicodeVersion()
	{
		if (Character.isDefined(0x1C89)) return "16.0";
		if (Character.isDefined(0x1E030)) return "15.0";
		if (Character.isDefined(0x061D)) return "14.0";
		return "13.0 or earlier";
	}
}
