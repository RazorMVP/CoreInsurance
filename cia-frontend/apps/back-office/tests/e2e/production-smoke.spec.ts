import { expect, test } from '@playwright/test';
import { mockBackOfficeApi } from './api-mocks';

const coreRoutes = [
  { path: '/dashboard', label: 'Dashboard', text: 'Active Policies' },
  { path: '/customers', label: 'Customers', text: 'Customers' },
  { path: '/quotation', label: 'Quotation', text: 'Quotation' },
  { path: '/policies', label: 'Policies', text: 'Policies' },
  { path: '/claims', label: 'Claims', text: 'Claims' },
  { path: '/finance', label: 'Finance', text: 'Finance' },
  { path: '/reports', label: 'Reports', text: 'Reports & Analytics' },
  { path: '/setup/products', label: 'Products', text: 'Products' },
];

test.beforeEach(async ({ page }) => {
  await mockBackOfficeApi(page);
});

test('renders the authenticated shell and dashboard', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByText('NubSure')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Customers' })).toBeVisible();
  await expect(page.getByText('Akinwale Nubeero')).toBeVisible();
});

for (const route of coreRoutes) {
  test(`renders ${route.label} route`, async ({ page }) => {
    await page.goto(route.path);

    await expect(page).toHaveURL(new RegExp(`${route.path.replace(/\//g, '\\/')}$`));
    await expect(page.getByText('NubSure')).toBeVisible();
    await expect(page.getByRole('main').first()).toContainText(route.text);
  });
}
