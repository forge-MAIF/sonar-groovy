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
package org.sonar.plugins.groovy;

import groovyjarjarantlr4.v4.runtime.CharStream;
import groovyjarjarantlr4.v4.runtime.CharStreams;
import groovyjarjarantlr4.v4.runtime.Token;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.groovy.parser.antlr4.GroovyLexer;
import org.gmetrics.result.MetricResult;
import org.gmetrics.result.NumberMetricResult;
import org.gmetrics.resultsnode.ClassResultsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.PropertyType;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.measure.Metric;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.resources.Qualifiers;
import org.sonar.plugins.groovy.foundation.Groovy;
import org.sonar.plugins.groovy.foundation.GroovyFileSystem;
import org.sonar.plugins.groovy.foundation.GroovyHighlighterAndTokenizer;
import org.sonar.plugins.groovy.gmetrics.GMetricsSourceAnalyzer;

public class GroovySensor implements Sensor {

  static final String IGNORE_HEADER_COMMENTS = "sonar.groovy.ignoreHeaderComments";

  private static final Logger LOG = LoggerFactory.getLogger(GroovySensor.class);

  private static final String CYCLOMATIC_COMPLEXITY_METRIC_NAME = "CyclomaticComplexity";

  private static final Set<String> EMPTY_COMMENT_LINES =
      Arrays.stream(new String[] {"/**", "/*", "*", "*/", "//"}).collect(Collectors.toSet());

  private final Configuration settings;
  private final FileLinesContextFactory fileLinesContextFactory;
  private final GroovyFileSystem groovyFileSystem;

  private int loc = 0;
  private int comments = 0;
  private int currentLine = 0;
  private FileLinesContext fileLinesContext;

  public GroovySensor(
      Configuration settings,
      FileLinesContextFactory fileLinesContextFactory,
      FileSystem fileSystem) {
    this.settings = settings;
    this.fileLinesContextFactory = fileLinesContextFactory;
    this.groovyFileSystem = new GroovyFileSystem(fileSystem);
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor.onlyOnLanguage(Groovy.KEY).name(this.toString());
  }

  @Override
  public void execute(SensorContext context) {
    if (groovyFileSystem.hasGroovyFiles()) {
      List<InputFile> inputFiles = groovyFileSystem.sourceInputFiles();
      computeBaseMetrics(context, inputFiles);
      computeGroovyMetrics(context, inputFiles);
      highlightFiles(context, groovyFileSystem.groovyInputFiles());
    }
  }

  private static void computeGroovyMetrics(SensorContext context, List<InputFile> inputFiles) {
    GMetricsSourceAnalyzer metricsAnalyzer =
        new GMetricsSourceAnalyzer(context.fileSystem(), inputFiles);

    metricsAnalyzer.analyze();

    for (Entry<InputFile, List<ClassResultsNode>> entry :
        metricsAnalyzer.resultsByFile().entrySet()) {
      processFile(context, entry.getKey(), entry.getValue());
    }
  }

  private static void processFile(
      SensorContext context, InputFile sonarFile, Collection<ClassResultsNode> results) {
    int classes = 0;
    int methods = 0;
    int complexity = 0;

    for (ClassResultsNode result : results) {
      classes += 1;

      methods += result.getChildren().size();

      Optional<MetricResult> cyclomaticComplexity =
          getCyclomaticComplexity(result.getMetricResults());
      if (cyclomaticComplexity.isPresent()) {
        int value =
            (Integer) ((NumberMetricResult) cyclomaticComplexity.get()).getValues().get("total");
        complexity += value;
      }
    }

    saveMetric(context, sonarFile, CoreMetrics.CLASSES, classes);
    saveMetric(context, sonarFile, CoreMetrics.FUNCTIONS, methods);
    saveMetric(context, sonarFile, CoreMetrics.COMPLEXITY, complexity);
  }

  private static Optional<MetricResult> getCyclomaticComplexity(List<MetricResult> metricResults) {
    return metricResults.stream()
        .filter(
            metricResult ->
                CYCLOMATIC_COMPLEXITY_METRIC_NAME.equals(metricResult.getMetric().getName()))
        .findAny();
  }

  private void computeBaseMetrics(SensorContext context, List<InputFile> inputFiles) {
    for (InputFile groovyFile : inputFiles) {
      computeBaseMetrics(context, groovyFile);
    }
  }

  private void computeBaseMetrics(SensorContext context, InputFile groovyFile) {
    loc = 0;
    comments = 0;
    currentLine = 0;
    fileLinesContext = fileLinesContextFactory.createFor(groovyFile);
    try (InputStreamReader streamReader =
        new InputStreamReader(groovyFile.inputStream(), groovyFile.charset())) {
      List<String> lines = IOUtils.readLines(groovyFile.inputStream(), groovyFile.charset());
      CharStream cs = CharStreams.fromReader(streamReader);
      GroovyLexer groovyLexer = new GroovyLexer(cs);
      groovyLexer.removeErrorListeners();
      // Iterate tokens directly to avoid full fill() which may surface fatal syntax errors
      Token token = groovyLexer.nextToken();
      Token nextToken = groovyLexer.nextToken();
      while (nextToken.getType() != Token.EOF) {
        handleToken(token, nextToken.getLine(), lines);
        token = nextToken;
        nextToken = groovyLexer.nextToken();
      }
      // handle last token before EOF
      handleToken(token, nextToken.getLine(), lines);
      saveMetric(context, groovyFile, CoreMetrics.NCLOC, loc);
      saveMetric(context, groovyFile, CoreMetrics.COMMENT_LINES, comments);
    } catch (Throwable e) {
      // Fallback: if Groovy lexer fails (e.g., unexpected char), compute metrics by simple line
      // parsing
      LOG.debug(
          "Groovy lexer failed for {}: {}. Falling back to line-based metrics.",
          groovyFile,
          e.toString());
      try {
        List<String> lines = IOUtils.readLines(groovyFile.inputStream(), groovyFile.charset());
        computeLineBasedMetrics(lines, context, groovyFile);
      } catch (IOException ioException) {
        LOG.error("Unable to read file for fallback: {}", groovyFile, ioException);
      }
    }
    fileLinesContext.save();
  }

  // Simple line-based metrics when lexing fails: counts non-empty code lines and comment lines
  // heuristically
  private void computeLineBasedMetrics(
      List<String> lines, SensorContext context, InputFile groovyFile) {
    boolean inBlock = false;
    for (int i = 0; i < lines.size(); i++) {
      String raw = lines.get(i);
      String line = raw == null ? "" : raw.trim();
      int lineNo = i + 1;
      if (line.isEmpty()) {
        continue;
      }
      boolean isCommentLine = false;
      if (inBlock) {
        isCommentLine = true;
        if (line.contains("*/")) {
          inBlock = false;
        }
      } else if (line.startsWith("//") || line.startsWith("#")) {
        isCommentLine = true;
      } else if (line.startsWith("/*")) {
        isCommentLine = true;
        if (!line.contains("*/")) {
          inBlock = true;
        }
      }

      if (isCommentLine) {
        if (isNotHeaderComment(lineNo)) {
          comments++;
        }
      } else {
        loc++;
        fileLinesContext.setIntValue(CoreMetrics.NCLOC_DATA_KEY, lineNo, 1);
      }
    }
    saveMetric(context, groovyFile, CoreMetrics.NCLOC, loc);
    saveMetric(context, groovyFile, CoreMetrics.COMMENT_LINES, comments);
  }

  private void handleToken(Token token, int nextTokenLine, List<String> lines) {
    int tokenType = token.getType();
    int tokenLine = token.getLine();
    if (isComment(tokenType, token)) {
      if (isNotHeaderComment(tokenLine)) {
        comments += nextTokenLine - tokenLine + 1 - numberEmptyLines(token, lines);
      }
    } else if (isNotWhitespace(tokenType, token) && tokenLine != currentLine) {
      loc++;
      fileLinesContext.setIntValue(CoreMetrics.NCLOC_DATA_KEY, tokenLine, 1);
      currentLine = tokenLine;
    }
  }

  private int numberEmptyLines(Token token, List<String> lines) {
    String text = token.getText();
    if (text == null) {
      return 0;
    }
    String[] relatedLines = text.split("\r?\n");
    long emptyLines =
        Arrays.stream(relatedLines).map(String::trim).filter(EMPTY_COMMENT_LINES::contains).count();
    return (int) emptyLines;
  }

  private static boolean isNotWhitespace(int tokenType, Token token) {
    String name = new GroovyLexer(null).getVocabulary().getSymbolicName(tokenType);
    if (name != null && ("WS".equals(name) || name.contains("NL") || name.contains("NLS"))) {
      return false;
    }
    String text = token.getText();
    return text != null && text.trim().length() > 0;
  }

  private static boolean isComment(int tokenType, Token token) {
    String name = new GroovyLexer(null).getVocabulary().getSymbolicName(tokenType);
    if (name == null) {
      return false;
    }
    return name.endsWith("COMMENT")
        || "ML_COMMENT".equals(name)
        || "SL_COMMENT".equals(name)
        || "SH_COMMENT".equals(name);
  }

  private static void highlightFiles(SensorContext context, List<InputFile> inputFiles) {
    for (InputFile inputFile : inputFiles) {
      new GroovyHighlighterAndTokenizer(inputFile).processFile(context);
    }
  }

  private static <T extends Serializable> void saveMetric(
      SensorContext context, InputComponent inputComponent, Metric<T> metric, T value) {
    context.<T>newMeasure().withValue(value).forMetric(metric).on(inputComponent).save();
  }

  private boolean isNotHeaderComment(int tokenLine) {
    return !(tokenLine == 1 && settings.getBoolean(IGNORE_HEADER_COMMENTS).orElse(true));
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }

  public static List<Object> getExtensions() {
    return Arrays.asList(
        GroovySensor.class,
        GroovySonarWayProfile.class,
        PropertyDefinition.builder(IGNORE_HEADER_COMMENTS)
            .name("Ignore Header Comments")
            .description(
                "If set to \"true\", the file headers (that are usually the same on each file: licensing information for example) are not considered as comments. "
                    + "Thus metrics such as \"Comment lines\" do not get incremented. "
                    + "If set to \"false\", those file headers are considered as comments and metrics such as \"Comment lines\" get incremented.")
            .category(Groovy.NAME)
            .subCategory("Base")
            .onQualifiers(Qualifiers.PROJECT)
            .defaultValue("true")
            .type(PropertyType.BOOLEAN)
            .build());
  }
}
