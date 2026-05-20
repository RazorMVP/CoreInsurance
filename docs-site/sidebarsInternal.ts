import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebarsInternal: SidebarsConfig = {
  internalApiSidebar: [
    {
      type: "doc",
      id: "overview",
      label: "Overview",
    },
    {
      type: "link",
      label: "API Reference (Scalar)",
      href: "/internal/api-reference",
    },
  ],
};

export default sidebarsInternal;
