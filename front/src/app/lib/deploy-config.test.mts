import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { describe, it } from "node:test";

describe("frontend deploy configuration", () => {
  it("builds the client bundle with NEXT_PUBLIC_API_BASE_URL from Docker build args", () => {
    const dockerfile = readFileSync(
      new URL("../../../Dockerfile", import.meta.url),
      "utf8",
    );
    const deployWorkflow = readFileSync(
      new URL("../../../../.github/workflows/deploy.yml", import.meta.url),
      "utf8",
    );

    assert.match(dockerfile, /ARG NEXT_PUBLIC_API_BASE_URL/);
    assert.match(dockerfile, /ENV NEXT_PUBLIC_API_BASE_URL=\$NEXT_PUBLIC_API_BASE_URL/);
    assert.match(deployWorkflow, /NEXT_PUBLIC_API_BASE_URL=/);
    assert.match(deployWorkflow, /build-args:\s*\|\s*\n\s*NEXT_PUBLIC_API_BASE_URL=/);
  });
});
