/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

class DummyGenerator private constructor(
  val plural: Boolean = false,
  val vowel: Boolean = false,
) {
  fun generate(
    language: String,
    number: Int,
    vowel: Boolean = false,
  ): String {
    val lang = language.lowercase()

    val pluralSuffixMap =
      mapOf(
        "sv" to "Dumma-$number",
        "es" to "Maniquíes-$number",
        "nl" to "Dummy's+$number",
        "de" to "Dummys",
      )

    val singularSuffixMap =
      mapOf(
        "fr" to "Jimmy-$number",
        "sv" to "Dummy-$number",
        "es" to "Maniquí-$number",
        "nl" to "Dummy+$number",
      )

    if (this.plural) {
      for ((prefix, suffix) in pluralSuffixMap) {
        if (lang.startsWith(prefix)) return suffix
      }
      return "Dummies"
    }

    for ((prefix, suffix) in singularSuffixMap) {
      if (lang.startsWith(prefix)) return suffix
    }

    if (vowel || this.vowel) return "Ina$number"
    return "Dummy$number"
  }

  companion object {
    private val INSTANCE = DummyGenerator()
    private val INSTANCE_PLURAL = DummyGenerator(plural = true)
    private val INSTANCE_VOWEL = DummyGenerator(vowel = true)
    private val INSTANCE_PLURAL_VOWEL = DummyGenerator(plural = true, vowel = true)

    fun getInstance(
      plural: Boolean = false,
      vowel: Boolean = false,
    ): DummyGenerator =
      when {
        plural && !vowel -> INSTANCE_PLURAL
        !plural && vowel -> INSTANCE_VOWEL
        plural && vowel -> INSTANCE_PLURAL_VOWEL
        else -> INSTANCE
      }
  }
}
