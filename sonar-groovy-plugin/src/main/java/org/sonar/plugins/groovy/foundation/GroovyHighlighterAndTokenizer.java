/*
 * Sonar Groovy Plugin
 * Copyright (C) 2010-2026 SonarQube Community
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.groovy.foundation;

import groovyjarjarantlr4.v4.runtime.CharStream;
import groovyjarjarantlr4.v4.runtime.CharStreams;
import groovyjarjarantlr4.v4.runtime.Token;
import groovyjarjarantlr4.v4.runtime.Vocabulary;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.groovy.parser.antlr4.GroovyLexer;
// Use Groovy's shaded ANTLR4 runtime packaged with Groovy
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.cpd.NewCpdTokens;
import org.sonar.api.batch.sensor.highlighting.NewHighlighting;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;

public class GroovyHighlighterAndTokenizer {

  private static final Logger LOG = LoggerFactory.getLogger(GroovyHighlighterAndTokenizer.class);

  // Keyword texts: used when lexer does not expose constants compatibly across versions
  private static final Set<String> KEYWORD_TEXTS =
      new HashSet<>(
          Arrays.asList(
              "as",
              "assert",
              "break",
              "case",
              "catch",
              "class",
              "continue",
              "def",
              "default",
              "else",
              "enum",
              "extends",
              "false",
              "finally",
              "for",
              "if",
              "implements",
              "import",
              "in",
              "instanceof",
              "interface",
              "native",
              "new",
              "null",
              "package",
              "private",
              "protected",
              "public",
              "return",
              "static",
              "super",
              "switch",
              "synchronized",
              "this",
              "throws",
              "throw",
              "trait",
              "transient",
              "true",
              "try",
              "void",
              "volatile",
              "while",
              "boolean",
              "byte",
              "char",
              "double",
              "float",
              "int",
              "long",
              "short"));

  private final InputFile inputFile;

  public GroovyHighlighterAndTokenizer(InputFile inputFile) {
    this.inputFile = inputFile;
  }

  public void processFile(SensorContext context) {
    List<GroovyToken> tokens = new ArrayList<>();

    try (InputStreamReader streamReader =
        new InputStreamReader(inputFile.inputStream(), context.fileSystem().encoding())) {
      // Build ANTLR4 CharStream and Groovy lexer
      CharStream cs = CharStreams.fromReader(streamReader);
      GroovyLexer groovyLexer = new GroovyLexer(cs);
      // Avoid default console error listener printing to stderr
      groovyLexer.removeErrorListeners();

      Token lastToken = null;
      while (true) {
        Token token;
        try {
          token = groovyLexer.nextToken();
        } catch (Throwable e) {
          int lineIdx = (lastToken != null && lastToken.getLine() > 0) ? lastToken.getLine() : 1;
          int colIdx = 1;
          // Try to extract precise line/column from GroovySyntaxError via reflection
          try {
            if ("org.apache.groovy.parser.antlr4.GroovySyntaxError"
                .equals(e.getClass().getName())) {
              try {
                Object l = e.getClass().getMethod("getLine").invoke(e);
                Object c = e.getClass().getMethod("getColumn").invoke(e);
                if (l instanceof Integer && ((Integer) l) > 0) {
                  lineIdx = (Integer) l;
                }
                if (c instanceof Integer && ((Integer) c) > 0) {
                  colIdx = (Integer) c;
                }
              } catch (Exception ignore) {
                // ignore; fall back to lastToken-based line
              }
            }
          } catch (Throwable ignore) {
            // ignore any reflection errors
          }

          // Read source line for context
          String lineText = null;
          try {
            List<String> srcLines =
                IOUtils.readLines(inputFile.inputStream(), context.fileSystem().encoding());
            if (lineIdx >= 1 && lineIdx <= srcLines.size()) {
              lineText = srcLines.get(lineIdx - 1);
            }
          } catch (IOException ioe) {
            // ignore inability to read for context
          }
          String caret =
              (lineText != null && colIdx >= 1) ? String.format("%" + colIdx + "s", "^") : "^";

          LOG.debug(
              "Groovy lexer failed for {} at line {}, column {}: {}. Skipping highlighting/CPD.{}{}{}",
              inputFile.uri(),
              lineIdx,
              colIdx,
              e.toString(),
              (lineText != null ? "\n" : ""),
              (lineText != null ? lineText : ""),
              (lineText != null ? "\n" + caret : ""));

          try {
            org.sonar.api.rule.RuleKey rk =
                org.sonar.api.rule.RuleKey.of(
                    org.sonar.plugins.groovy.codenarc.CodeNarcRulesDefinition.REPOSITORY_KEY,
                    "lexer-failure");
            org.sonar.api.batch.sensor.issue.NewIssue newIssue = context.newIssue().forRule(rk);
            org.sonar.api.batch.sensor.issue.NewIssueLocation location =
                newIssue
                    .newLocation()
                    .on(inputFile)
                    .at(inputFile.selectLine(lineIdx))
                    .message(
                        "Groovy lexer failed at line "
                            + lineIdx
                            + ", column "
                            + colIdx
                            + ": "
                            + e.getMessage());
            newIssue.at(location).save();
          } catch (Throwable ignore) {
            // ignore secondary failures, keep going
          }
          break;
        }
        if (token == null || token.getType() == Token.EOF) {
          break;
        }
        String text = token.getText();
        Optional<TypeOfText> maybeType =
            typeOfText(token.getType(), text, groovyLexer.getVocabulary());
        TypeOfText typeOfText = maybeType.orElse(null);

        if (StringUtils.isNotBlank(text)) {
          int startLine = token.getLine();
          int startColumn = token.getCharPositionInLine() + 1;
          int endLine = startLine;
          int endColumn;
          int lastNewline = text.lastIndexOf('\n');
          if (lastNewline >= 0) {
            int newlines = (int) text.chars().filter(ch -> ch == '\n').count();
            endLine = startLine + newlines;
            String tail = text.substring(lastNewline + 1);
            endColumn = tail.length() + 1; // columns are 1-based
          } else {
            endColumn = startColumn + text.length();
          }

          tokens.add(
              new GroovyToken(
                  startLine,
                  startColumn,
                  endLine,
                  endColumn,
                  getImage(token.getType(), text, groovyLexer.getVocabulary()),
                  typeOfText));
        }
        lastToken = token;
      }
    } catch (IOException e) {
      LOG.error("Unable to read file: " + inputFile.uri(), e);
    }

    if (!tokens.isEmpty()) {
      boolean isNotTest = inputFile.type() != InputFile.Type.TEST;
      NewCpdTokens cpdTokens = isNotTest ? context.newCpdTokens().onFile(inputFile) : null;
      NewHighlighting highlighting = context.newHighlighting().onFile(inputFile);
      for (GroovyToken groovyToken : tokens) {
        if (isNotTest) {
          cpdTokens =
              cpdTokens.addToken(
                  groovyToken.startLine,
                  groovyToken.startColumn,
                  groovyToken.endLine,
                  groovyToken.endColumn,
                  groovyToken.value);
        }
        if (groovyToken.typeOfText != null) {
          highlighting =
              highlighting.highlight(
                  groovyToken.startLine,
                  groovyToken.startColumn,
                  groovyToken.endLine,
                  groovyToken.endColumn,
                  groovyToken.typeOfText);
        }
      }
      highlighting.save();
      if (isNotTest) {
        cpdTokens.save();
      }
    }
  }

  private String getImage(int tokenType, String text, Vocabulary vocab) {
    String name = vocab.getSymbolicName(tokenType);
    if (name != null && (name.contains("STRING") || name.contains("REGEX"))) {
      return "LITERAL";
    }
    return text;
  }

  private Optional<TypeOfText> typeOfText(int type, String text, Vocabulary vocab) {
    String name = vocab.getSymbolicName(type);
    TypeOfText result = null;

    if (name != null) {
      if (name.endsWith("COMMENT")
          || name.equals("ML_COMMENT")
          || name.equals("SL_COMMENT")
          || name.equals("SH_COMMENT")) {
        result = TypeOfText.COMMENT;
      } else if (name.contains("STRING") || name.contains("REGEX")) {
        result = TypeOfText.STRING;
      } else if (KEYWORD_TEXTS.contains(text)) {
        result = TypeOfText.KEYWORD;
      }
    }

    if (result == TypeOfText.COMMENT && text.startsWith("/**")) {
      result = TypeOfText.STRUCTURED_COMMENT;
    }

    return Optional.ofNullable(result);
  }

  private static class GroovyToken {
    final int startLine;
    final int startColumn;
    final int endLine;
    final int endColumn;
    final String value;
    @Nullable final TypeOfText typeOfText;

    public GroovyToken(
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String value,
        @Nullable TypeOfText typeOfText) {
      this.startLine = startLine;
      this.startColumn = startColumn - 1;
      this.endLine = endLine;
      this.endColumn = endColumn - 1;
      this.value = value;
      this.typeOfText = typeOfText;
    }
  }
}
