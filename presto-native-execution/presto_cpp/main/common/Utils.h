/*
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
#pragma once
#include <folly/io/async/SSLContext.h>
#include <glog/logging.h>
#include "presto_cpp/presto_protocol/presto_protocol.h"

namespace facebook::presto::util {

#define PRESTO_STARTUP_LOG_PREFIX "[PRESTO_STARTUP] "
#define PRESTO_STARTUP_LOG(severity) LOG(severity) << PRESTO_STARTUP_LOG_PREFIX

#define PRESTO_SHUTDOWN_LOG_PREFIX "[PRESTO_SHUTDOWN] "
#define PRESTO_SHUTDOWN_LOG(severity) \
  LOG(severity) << PRESTO_SHUTDOWN_LOG_PREFIX

protocol::DateTime toISOTimestamp(uint64_t timeMilli);

std::shared_ptr<folly::SSLContext> createSSLContext(
    const std::string& clientCertAndKeyPath,
    const std::string& ciphers);

/// Returns process-wide CPU time in nanoseconds.
long getProcessCpuTime();

std::string taskNumbersToString(const std::array<size_t, 5>& taskNumbers);

} // namespace facebook::presto::util
