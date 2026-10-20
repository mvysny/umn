package com.gitlab.mvysny.umn.core.fulltext

import org.junit.jupiter.api.Test
import kotlin.test.expect

class PorterStemmerTest {
    private val stemmer = PorterStemmer()

    /**
     * Word → stem pairs from the examples in M.F. Porter's paper, "An algorithm for suffix stripping" (1980),
     * run through all five steps.
     */
    private val porterExamples = mapOf(
        // step 1a
        "caresses" to "caress", "ponies" to "poni", "ties" to "ti", "caress" to "caress", "cats" to "cat",
        // step 1b
        "feed" to "feed", "agreed" to "agre", "plastered" to "plaster", "bled" to "bled", "motoring" to "motor", "sing" to "sing",
        "conflated" to "conflat", "troubled" to "troubl", "sized" to "size", "hopping" to "hop", "tanned" to "tan",
        "falling" to "fall", "hissing" to "hiss", "fizzed" to "fizz", "failing" to "fail", "filing" to "file",
        // step 1c
        "happy" to "happi", "sky" to "sky",
        // step 2
        "relational" to "relat", "conditional" to "condit", "rational" to "ration", "valenci" to "valenc",
        "hesitanci" to "hesit", "digitizer" to "digit", "conformabli" to "conform", "radicalli" to "radic",
        "differentli" to "differ", "vileli" to "vile", "analogousli" to "analog", "vietnamization" to "vietnam",
        "predication" to "predic", "operator" to "oper", "feudalism" to "feudal", "decisiveness" to "decis",
        "hopefulness" to "hope", "callousness" to "callous", "formaliti" to "formal", "sensitiviti" to "sensit",
        "sensibiliti" to "sensibl",
        // step 3
        "triplicate" to "triplic", "formative" to "form", "formalize" to "formal", "electriciti" to "electr",
        "electrical" to "electr", "hopeful" to "hope", "goodness" to "good",
        // step 4
        "revival" to "reviv", "allowance" to "allow", "inference" to "infer", "airliner" to "airlin",
        "gyroscopic" to "gyroscop", "adjustable" to "adjust", "defensible" to "defens", "irritant" to "irrit",
        "replacement" to "replac", "adjustment" to "adjust", "dependent" to "depend", "adoption" to "adopt",
        "homologou" to "homolog", "communism" to "commun", "activate" to "activ", "angulariti" to "angular",
        "homologous" to "homolog", "effective" to "effect", "bowdlerize" to "bowdler",
        // step 5
        "probate" to "probat", "rate" to "rate", "cease" to "ceas", "controll" to "control", "roll" to "roll",
        // all steps
        "generalizations" to "gener", "oscillators" to "oscil",
    )

    @Test fun `stems the examples from Porter's paper`() {
        val wrong = porterExamples.mapValues { stemmer.stem(it.key) }.filter { it.value != porterExamples[it.key] }
        expect(mapOf()) { wrong }
    }

    @Test fun `short words are left alone`() {
        expect("a") { stemmer.stem("a") }
        expect("is") { stemmer.stem("is") }
        expect("") { stemmer.stem("") }
    }

    @Test fun `stem char array`() {
        expect(true) { stemmer.stem("cats".toCharArray()) }
        expect("cat") { stemmer.toString() }
        expect(false) { stemmer.stem("cat".toCharArray()) }
        expect("cat") { String(stemmer.resultBuffer, 0, stemmer.resultLength) }
    }

    @Test fun `stem a portion of a char array`() {
        expect(true) { stemmer.stem("xxponiesxx".toCharArray(), 2, 6) }
        expect("poni") { stemmer.toString() }
    }

    @Test fun `stem characters added one by one`() {
        "hopping".forEach { stemmer.add(it) }
        expect(true) { stemmer.stem() }
        expect("hop") { stemmer.toString() }
        stemmer.reset()
        "sky".forEach { stemmer.add(it) }
        expect(false) { stemmer.stem() }
        expect("sky") { stemmer.toString() }
    }

    @Test fun `words longer than the initial buffer`() {
        val long = "x".repeat(100) + "cats"
        expect("x".repeat(100) + "cat") { stemmer.stem(long) }
        stemmer.reset()
        long.forEach { stemmer.add(it) }
        expect(true) { stemmer.stem() }
        expect("x".repeat(100) + "cat") { stemmer.toString() }
    }
}
