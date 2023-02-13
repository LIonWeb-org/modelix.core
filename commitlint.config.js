module.exports = {
  extends: ["@commitlint/config-conventional"],
  rules: {
    "scope-enum": [
      2,
      "always",
      [
        "kotlin-js-store",
        "light-model-client",
        "light-model-server",
        "metamodel-export-mps",
        "metamodel-generator",
        "metamodel-gradle",
        "model-api",
        "model-client",
        "model-server",
        "ts-model-api",
      ],
    ],
  },
};
