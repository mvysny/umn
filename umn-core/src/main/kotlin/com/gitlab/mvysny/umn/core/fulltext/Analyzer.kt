package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.ThreadLocalCache
import java.text.BreakIterator
import java.text.Normalizer
import java.util.*

/**
 * A text analyzer. See [JavaSimpleAnalyzer] for the simple default implementation.
 */
public interface Analyzer {
    /**
     * Splits given text to words. Removes all punctuation marks.
     */
    public fun splitToWords(text: String): List<String>

    /**
     * Checks if given [word] is a stop word.
     * @param word not blank, stemmed, normalized.
     */
    public fun isStopWord(word: String): Boolean

    /**
     * Normalizes the word - removes diacritic such as caron and accents, and uses lower-case.
     */
    public fun normalize(word: String): String

    /**
     * Computes the basic stem for given [word] if possible. If not, just return the original word.
     * @param word the word to stem, not blank, normalized.
     */
    public fun stem(word: String): String

    /**
     * Returns search tokens from given text.
     */
    public fun getTokens(text: String): Set<String> = splitToWords(text)
            .map { stem(normalize(it)) }
            .filter { !isStopWord(it) }
            .toSet()
}

private val porterStemmerCache: PorterStemmer by ThreadLocalCache { PorterStemmer() }

/**
 * A very simple implementation of [Analyzer]:
 * * Uses English-based porter stemmer for [stem]
 * * Uses Java Normalizer to remove diacritic: https://stackoverflow.com/questions/3322152/is-there-a-way-to-get-rid-of-accents-and-convert-a-whole-string-to-regular-lette
 * * Uses English [STOP_WORDS] for [isStopWord].
 * * Uses [BreakIterator.getWordInstance] to [splitToWords].
 */
public class JavaSimpleAnalyzer : Analyzer {
    override fun stem(word: String): String = porterStemmerCache.stem(word)
    override fun normalize(word: String): String {
        var string = Normalizer.normalize(word, Normalizer.Form.NFKD)
        string = string.replace(ACCENT_MATCHER, "")
        string = string.lowercase(Locale.getDefault())
        return string
    }

    override fun isStopWord(word: String): Boolean = STOP_WORDS.contains(word)

    public companion object {
        public val ACCENT_MATCHER: Regex = "\\p{M}".toRegex()
        public val STOP_WORDS: Set<String> = setOf("a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is", "it", "no", "not", "of", "on", "or", "such", "that", "the", "their", "then", "there", "these", "they", "this", "to", "was", "will", "with")
    }

    override fun splitToWords(text: String): List<String> {
        val bi = BreakIterator.getWordInstance()
        bi.setText(text)
        val result = LinkedList<String>()
        while (true) {
            val current = bi.current()
            val next = bi.next()
            if (next == BreakIterator.DONE) {
                break
            }
            val word = text.substring(current, next).trim()
            if (word.isEmpty()) {
                continue
            }
            val c = word.codePointAt(0)
            if (c.isAlphabetic() || Character.isDigit(c)) {
                result.add(word)
            }
        }
        return result
    }
}

/**
 * Determines if the specified character (Unicode code point) is an alphabet.
 * <p>
 * A character is considered to be alphabetic if its general category type,
 * provided by {@link Character#getType(int) getType(codePoint)}, is any of
 * the following:
 * <ul>
 * <li> <code>UPPERCASE_LETTER</code>
 * <li> <code>LOWERCASE_LETTER</code>
 * <li> <code>TITLECASE_LETTER</code>
 * <li> <code>MODIFIER_LETTER</code>
 * <li> <code>OTHER_LETTER</code>
 * <li> <code>LETTER_NUMBER</code>
 * </ul>
 * or it has contributory property Other_Alphabetic as defined by the
 * Unicode Standard.
 *
 * @param   codePoint the character (Unicode code point) to be tested.
 * @return  <code>true</code> if the character is a Unicode alphabet
 *          character, <code>false</code> otherwise.
 * @since   1.7
 */
public fun Int.isAlphabetic(): Boolean {
    // do not use Character.isAlphabetic(c): it also counts Other_Alphabetic, which would change
    // the tokens of existing full-text indices
    val type = Character.getType(this)
    // triedy su zobrate z javadocu k Character.isAlphabetic
    return type == Character.UPPERCASE_LETTER.toInt() ||
            type == Character.LOWERCASE_LETTER.toInt() ||
            type == Character.TITLECASE_LETTER.toInt() ||
            type == Character.MODIFIER_LETTER.toInt() ||
            type == Character.OTHER_LETTER.toInt() ||
            type == Character.LETTER_NUMBER.toInt()
}
