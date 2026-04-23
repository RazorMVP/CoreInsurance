import { themes as prismThemes } from "prism-react-renderer";
import type { Config } from "@docusaurus/types";
import type * as Preset from "@docusaurus/preset-classic";

const config: Config = {
  title: "CIA Documentation",
  tagline: "Core Insurance Application — Developer & Partner Reference",
  favicon: "img/favicon.png",

  url: process.env.DOCUSAURUS_URL || "https://cia-docs.vercel.app",
  baseUrl: process.env.DOCUSAURUS_BASE_URL || "/",

  organizationName: "RazorMVP",
  projectName: "CoreInsurance",
  trailingSlash: false,

  onBrokenLinks: "throw",
  onBrokenMarkdownLinks: "warn",

  i18n: {
    defaultLocale: "en",
    locales: ["en"],
  },

  plugins: [
    [
      "@scalar/docusaurus",
      {
        label: "Partner API Reference",
        route: "/partner/api-reference",
        configuration: {
          spec: { url: "/openapi.json" },
          darkMode: true,
          defaultHttpClient: {
            targetKey: "javascript",
            clientKey: "fetch",
          },
        },
      },
    ],
    [
      "@scalar/docusaurus",
      {
        id: "internal-api-reference",
        label: "Internal API Reference",
        route: "/internal/api-reference",
        configuration: {
          spec: { url: "/internal-api.json" },
          darkMode: true,
          defaultHttpClient: {
            targetKey: "javascript",
            clientKey: "fetch",
          },
        },
      },
    ],
  ],

  presets: [
    [
      "classic",
      {
        docs: {
          sidebarPath: "./sidebars.ts",
          editUrl:
            "https://github.com/RazorMVP/CoreInsurance/tree/main/docs-site/",
        },
        blog: false,
        theme: {
          customCss: "./src/css/custom.css",
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: "CIA Docs",
      logo: {
        alt: "Nubeero Logo",
        src: "img/logo.png",
      },
      items: [
        {
          type: "docSidebar",
          sidebarId: "internalSidebar",
          position: "left",
          label: "Developer Guide",
        },
        {
          type: "docSidebar",
          sidebarId: "partnerApiSidebar",
          position: "left",
          label: "Partner API",
        },
        {
          to: "/partner/api-reference",
          position: "left",
          label: "Partner API",
        },
        {
          to: "/internal/api-reference",
          position: "left",
          label: "Internal API",
        },
        {
          href: "https://github.com/RazorMVP/CoreInsurance",
          label: "GitHub",
          position: "right",
        },
      ],
    },
    footer: {
      style: "dark",
      links: [
        {
          title: "Docs",
          items: [
            { label: "Architecture Overview", to: "/docs/architecture/overview" },
            { label: "Local Setup", to: "/docs/guides/local-setup" },
            { label: "Module Reference", to: "/docs/architecture/modules" },
          ],
        },
        {
          title: "Partner Resources",
          items: [
            { label: "API Explorer", to: "/partner/api-reference" },
            { label: "Authentication Guide", to: "/docs/partner/authentication" },
            {
              label: "Postman Collection",
              href: "https://github.com/RazorMVP/CoreInsurance/blob/main/cia-backend/cia-partner-api/docs/postman_collection.json",
            },
          ],
        },
      ],
      copyright: `© ${new Date().getFullYear()} Nubeero Technologies`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: [
        "java",
        "bash",
        "json",
        "yaml",
        "python",
        "kotlin",
      ],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
