/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <sys/stat.h>   // umask
#include <sys/types.h>  // umask
#include <fstream>
#include <memory>
#include <ostream>
#include <sstream>
#include <string>
#include <vector>

#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/CommandLineOptions.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"

using android::ApkAssets;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::CommandLineOptions;
using android::idmap2::Idmap;

bool Create(const std::vector<std::string>& args, std::ostream& out_error) {
  std::string target_apk_path, overlay_apk_path, idmap_path;

  const CommandLineOptions opts =
      CommandLineOptions("idmap2 create")
          .MandatoryOption("--target-apk-path",
                           "input: path to apk which will have its resources overlaid",
                           &target_apk_path)
          .MandatoryOption("--overlay-apk-path",
                           "input: path to apk which contains the new resource values",
                           &overlay_apk_path)
          .MandatoryOption("--idmap-path", "output: path to where to write idmap file",
                           &idmap_path);
  if (!opts.Parse(args, out_error)) {
    return false;
  }

  const std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  if (!target_apk) {
    out_error << "error: failed to load apk " << target_apk_path << std::endl;
    return false;
  }

  const std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  if (!overlay_apk) {
    out_error << "error: failed to load apk " << overlay_apk_path << std::endl;
    return false;
  }

  const std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk, out_error);
  if (!idmap) {
    return false;
  }

  umask(0133);  // u=rw,g=r,o=r
  std::ofstream fout(idmap_path);
  if (fout.fail()) {
    out_error << "failed to open idmap path " << idmap_path << std::endl;
    return false;
  }
  BinaryStreamVisitor visitor(fout);
  idmap->accept(&visitor);
  fout.close();
  if (fout.fail()) {
    out_error << "failed to write to idmap path " << idmap_path << std::endl;
    return false;
  }

  return true;
}