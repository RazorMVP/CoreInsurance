import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
  internalSidebar: [
    {
      type: "category",
      label: "Architecture",
      items: [
        "architecture/overview",
        "architecture/modules",
        "architecture/multi-tenancy",
        "architecture/security",
        "architecture/workflows",
        "architecture/integrations",
      ],
    },
    {
      type: "category",
      label: "Setup Guides",
      items: [
        "guides/local-setup",
        "guides/tenant-provisioning",
        "guides/environment-variables",
        "guides/database-migrations",
      ],
    },
    {
      type: "category",
      label: "Development",
      items: [
        "development/coding-standards",
        "development/testing",
        "development/adding-a-module",
      ],
    },
  ],

  partnerApiSidebar: [
    {
      type: "category",
      label: "Getting Started",
      items: [
        "partner/overview",
        "partner/authentication",
        "partner/webhooks",
        "partner/rate-limiting",
        "partner/sandbox",
      ],
    },
    {
      type: "category",
      label: "API Reference",
      items: [
        {
          type: "link",
          label: "Interactive API Explorer (Swagger UI)",
          href: "https://razormvp.github.io/CoreInsurance/partner/docs",
        },
        {
          type: "link",
          label: "OpenAPI Specification (JSON)",
          href: "https://razormvp.github.io/CoreInsurance/openapi.json",
        },
      ],
    },
  ],
};

export default sidebars;
