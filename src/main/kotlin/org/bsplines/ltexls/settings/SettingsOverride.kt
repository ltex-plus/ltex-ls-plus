/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.settings

import java.util.logging.Level

@Suppress("TooManyFunctions")
class SettingsOverride(
  private val _inner: Settings,
) {
  private var enabled: Boolean? = null
  private var languageShortCode: String? = null
  private var allDictionaries: Map<String, Map<String, SetUpdate>>? = null
  private var allRules: Map<String, Map<String, MapUpdate<Boolean>>>? = null
  private var allHiddenFalsePositives: Map<String, Map<HiddenFalsePositive, SetUpdate>>? = null
  private var bibtexFields: Map<String, MapUpdate<Boolean>>? = null
  private var latexCommands: Map<String, MapUpdate<String>>? = null
  private var latexEnvironments: Map<String, MapUpdate<String>>? = null
  private var markdownNodes: Map<String, MapUpdate<String>>? = null
  private var enablePickyRules: Boolean? = null
  private var motherTongueShortCode: String? = null
  private var languageModelRulesDirectory: String? = null
  private var logLevel: Level? = null

  val activeLanguageShortCode: String
    get() = languageShortCode ?: _inner.languageShortCode

  @Suppress("LongMethod", "CyclomaticComplexMethod")
  fun toSettings(): Settings {
    var newSettings = _inner
    if (enabled == false) {
      newSettings = newSettings.copy(_enabled = setOf())
    }
    languageShortCode?.let {
      newSettings = newSettings.copy(_languageShortCode = it)
    }
    allDictionaries?.let {
      newSettings =
        newSettings.copy(
          _allDictionaries =
            overrideMapSet(
              // hacky: get _allDictionaries.
              newSettings.getModifiedDictionary(newSettings.dictionary),
              it,
            ),
        )
    }
    allRules?.let {
      // REMOVE -> REMOVE, REMOVE
      // INSERT(true) -> INSERT, REMOVE
      // INSERT(false) -> REMOVE, INSERT
      fun mapSetOverride(invert: Boolean): Map<String, Map<String, SetUpdate>> =
        it
          .map { (lang, rules) ->
            lang to
              rules
                .map { (rule, update) ->
                  rule to
                    if (update is MapUpdate.SetValue<Boolean>) {
                      SetUpdate.fromBoolean(update.value).inverted(invert)
                    } else {
                      SetUpdate.REMOVE
                    }
                }.toMap()
          }.toMap()
      newSettings =
        newSettings.copy(
          _allEnabledRules =
            overrideMapSet(
              _inner.getModifiedEnabledRules(newSettings.enabledRules),
              mapSetOverride(false),
            ),
          _allDisabledRules =
            overrideMapSet(
              _inner.getModifiedDisabledRules(newSettings.disabledRules),
              mapSetOverride(true),
            ),
        )
    }
    allHiddenFalsePositives?.let {
      newSettings =
        newSettings.copy(
          _allHiddenFalsePositives =
            overrideMapSet(
              newSettings.getModifiedHiddenFalsePositives(newSettings.hiddenFalsePositives),
              it,
            ),
        )
    }
    bibtexFields?.let {
      newSettings =
        newSettings.copy(
          _bibtexFields = overrideMap(newSettings.bibtexFields, it),
        )
    }
    latexCommands?.let {
      newSettings =
        newSettings.copy(
          _latexCommands = overrideMap(newSettings.latexCommands, it),
        )
    }
    latexEnvironments?.let {
      newSettings =
        newSettings.copy(
          _latexEnvironments = overrideMap(newSettings.latexEnvironments, it),
        )
    }
    markdownNodes?.let {
      newSettings =
        newSettings.copy(
          _markdownNodes = overrideMap(newSettings.markdownNodes, it),
        )
    }
    enablePickyRules?.let { newSettings = newSettings.copy(_enablePickyRules = it) }
    motherTongueShortCode?.let { newSettings = newSettings.copy(_motherTongueShortCode = it) }
    languageModelRulesDirectory?.let {
      newSettings = newSettings.copy(_languageModelRulesDirectory = it)
    }
    logLevel?.let { newSettings = newSettings.copy(_logLevel = it) }
    return newSettings
  }

  fun updateEnabled(enabled: Boolean?) {
    // default / disabled
    this@SettingsOverride.enabled = enabled ?: true
  }

  fun updateLanguageShortCode(code: String?) {
    // code / default
    languageShortCode = code
  }

  fun updateCurrentDictionary(
    word: String,
    included: Boolean?,
  ) {
    // included / not included / fallback to _inner
    allDictionaries =
      overrideMapMap(
        allDictionaries,
        mapOf(activeLanguageShortCode to mapOf(word to MapUpdate.fromBoolean(included))),
      )
  }

  fun updateCurrentRule(
    rule: String,
    enabled: Boolean?,
  ) {
    // enabled / disabled / fallback
    allRules =
      overrideMapMap(
        allRules,
        mapOf(activeLanguageShortCode to mapOf(rule to MapUpdate.metaUpdate(enabled))),
      )
  }

  fun updateCurrentFalsePositive(
    falsePositive: HiddenFalsePositive,
    active: Boolean?,
  ) {
    // enabled / disabled / fallback
    allHiddenFalsePositives =
      overrideMapMap(
        allHiddenFalsePositives,
        mapOf(activeLanguageShortCode to mapOf(falsePositive to MapUpdate.fromBoolean(active))),
      )
  }

  fun updateBibtexField(
    field: String,
    checked: Boolean?,
  ) {
    // checked / unchecked / fallback
    bibtexFields =
      overrideMap(
        bibtexFields,
        mapOf(field to MapUpdate.metaUpdate(checked)),
      )
  }

  fun updateLatexCommand(
    command: String,
    action: String?,
  ) {
    latexCommands =
      overrideMap(
        latexCommands,
        mapOf(command to MapUpdate.metaUpdate(action)),
      )
  }

  fun updateLatexEnvironment(
    environment: String,
    action: String?,
  ) {
    latexEnvironments =
      overrideMap(
        latexEnvironments,
        mapOf(environment to MapUpdate.metaUpdate(action)),
      )
  }

  fun updateMarkdownNode(
    node: String,
    action: String?,
  ) {
    markdownNodes =
      overrideMap(
        markdownNodes,
        mapOf(node to MapUpdate.metaUpdate(action)),
      )
  }

  fun updatePickyRules(enabled: Boolean?) {
    enablePickyRules = enabled
  }

  fun updateMotherTongueShortCode(code: String?) {
    motherTongueShortCode = code
  }

  fun updateLanguageModelRulesDirectory(dir: String?) {
    languageModelRulesDirectory = dir
  }

  fun updateLogLevel(level: Level?) {
    logLevel = level
  }

  private companion object {
    sealed class MapUpdate<V> {
      class SetValue<V>(
        val value: V,
      ) : MapUpdate<V>()

      class RemoveValue<V> : MapUpdate<V>()

      companion object {
        fun <V> fromNullable(value: V?): MapUpdate<V> = value?.let { SetValue(it) } ?: RemoveValue()

        fun fromBoolean(boolean: Boolean?): MapUpdate<SetUpdate> {
          // return value updates a set: null -> ignore, true -> insert, false -> remove
          return if (boolean == null) RemoveValue() else SetValue(SetUpdate.fromBoolean(boolean))
        }

        fun <V> metaUpdate(value: V?): MapUpdate<MapUpdate<V>> =
          if (value == null) RemoveValue() else SetValue(SetValue(value))
      }

      fun <K> performOn(
        map: MutableMap<K, V>,
        key: K,
      ) {
        if (this is SetValue) {
          map[key] = this.value
        } else {
          map.remove(key)
        }
      }
    }

    enum class SetUpdate {
      INSERT,
      REMOVE,
      ;

      companion object {
        fun fromBoolean(boolean: Boolean): SetUpdate = if (boolean) INSERT else REMOVE
      }

      fun <V> performOn(
        set: MutableSet<V>,
        value: V,
      ) {
        when (this) {
          INSERT -> set.add(value)
          REMOVE -> set.remove(value)
        }
      }

      fun inverted(invert: Boolean): SetUpdate {
        if (!invert) return this
        return when (this) {
          INSERT -> REMOVE
          REMOVE -> INSERT
        }
      }
    }

    fun <K1, K2, V> overrideMapMap(
      old: Map<K1, Map<K2, V>>?,
      override: Map<K1, Map<K2, MapUpdate<V>>>,
    ): Map<K1, Map<K2, V>> {
      val new = old?.toMutableMap() ?: mutableMapOf()
      for ((mapKey, mapOverride) in override) {
        new[mapKey] = overrideMap(new[mapKey], mapOverride)
      }
      return new
    }

    fun <K, V> overrideMapSet(
      old: Map<K, Set<V>>?,
      override: Map<K, Map<V, SetUpdate>>,
    ): Map<K, Set<V>> {
      val new = old?.toMutableMap() ?: mutableMapOf()
      for ((mapKey, setOverride) in override) {
        new[mapKey] = overrideSet(new[mapKey], setOverride)
      }
      return new
    }

    fun <V> overrideSet(
      old: Set<V>?,
      override: Map<V, SetUpdate>,
    ): MutableSet<V> {
      val new = old?.toMutableSet() ?: mutableSetOf()
      for ((setElement, setUpdate) in override) {
        setUpdate.performOn(new, setElement)
      }
      return new
    }

    fun <K, V> overrideMap(
      old: Map<K, V>?,
      override: Map<K, MapUpdate<V>>,
    ): MutableMap<K, V> {
      val new = old?.toMutableMap() ?: mutableMapOf()
      for ((mapKey, mapUpdate) in override) {
        mapUpdate.performOn(new, mapKey)
      }
      return new
    }
  }
}
