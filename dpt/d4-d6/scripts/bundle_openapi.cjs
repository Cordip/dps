#!/usr/bin/env node

const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");

const repoRoot = path.resolve(__dirname, "..");
const inputPath = path.join(repoRoot, "docs", "openapi", "openapi.yaml");
const outputPath = path.join(repoRoot, "docs", "openapi", "openapi.bundle.yaml");

function requireFromModuleDir(moduleDir, packageName) {
  const requireFromDir = createRequire(path.join(moduleDir, "noop.js"));
  return requireFromDir(packageName);
}

function findToolModuleDir() {
  const explicitDir = process.env.OPENAPI_TOOLS_NODE_MODULES;
  if (explicitDir) {
    return explicitDir;
  }

  const candidates = [
    path.join(repoRoot, "node_modules"),
    "/home/cordis/.npm/_npx/e8d1761dc3fb6aab/node_modules",
  ];

  for (const candidate of candidates) {
    if (fs.existsSync(path.join(candidate, "@apidevtools", "json-schema-ref-parser"))) {
      return candidate;
    }
  }

  throw new Error(
    "OpenAPI bundle dependencies were not found. Install @apidevtools/json-schema-ref-parser and js-yaml, " +
      "or set OPENAPI_TOOLS_NODE_MODULES to a node_modules directory containing them.",
  );
}

try {
  const toolModuleDir = findToolModuleDir();
  const refParser = requireFromModuleDir(toolModuleDir, "@apidevtools/json-schema-ref-parser");
  const yaml = requireFromModuleDir(toolModuleDir, "js-yaml");

  refParser.bundle(inputPath).then((bundled) => {
    const output = yaml.dump(bundled, {
      lineWidth: 120,
      noRefs: true,
      sortKeys: false,
    });

    fs.writeFileSync(outputPath, output, "utf8");
    console.log(`Bundled ${path.relative(repoRoot, inputPath)} -> ${path.relative(repoRoot, outputPath)}`);
  }).catch((error) => {
    console.error(error);
    process.exit(1);
  });
} catch (error) {
  console.error(error);
  process.exit(1);
}
