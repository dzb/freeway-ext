/*
 * Copyright 2026 dzb
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jujin.freeway.bench.cli;

/**
 * A command-line usage error — a bad flag, an unknown value, a missing prerequisite. The message is
 * the whole report: the CLI prints it without a stack trace and exits with the usage code, unlike
 * an internal failure.
 */
public final class UsageException extends RuntimeException {

  public UsageException(String message) {
    super(message);
  }
}
