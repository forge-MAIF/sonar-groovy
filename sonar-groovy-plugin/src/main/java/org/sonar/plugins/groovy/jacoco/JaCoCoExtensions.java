/*
 * Sonar Groovy Plugin
 * Copyright (C) 2010-2023 SonarQube Community
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
package org.sonar.plugins.groovy.jacoco;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JaCoCoExtensions {

  private static final Logger LOG = LoggerFactory.getLogger(JaCoCoExtensions.class);

  private JaCoCoExtensions() {}

  public static List<Object> getExtensions() {
    List<Object> extensions = new ArrayList<>();

    extensions.addAll(JaCoCoConfiguration.getPropertyDefinitions());
    extensions.addAll(
        Arrays.asList(
            JaCoCoConfiguration.class,
            // Unit tests
            JaCoCoSensor.class));

    return extensions;
  }

  public static Logger logger() {
    return LOG;
  }
}
