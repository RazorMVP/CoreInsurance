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
      label: "Audit & Compliance",
      items: [
        "audit/overview",
        "audit/api-reference",
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
    {
      type: "link",
      label: "Internal API Reference",
      href: "/internal/api-reference",
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
          label: "Interactive API Explorer",
          href: "/partner/api-reference",
        },
        {
          type: "link",
          label: "OpenAPI Specification (JSON)",
          href: "/openapi.json",
        },
      ],
    },
  ],
};

export default sidebars;
