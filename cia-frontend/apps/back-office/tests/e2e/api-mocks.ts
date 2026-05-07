import type { Page, Route } from '@playwright/test';

const emptyPage = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 20,
};

export async function mockBackOfficeApi(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: dataFor(url.pathname), errors: [] }),
    });
  });
}

function dataFor(pathname: string): unknown {
  if (pathname.endsWith('/dashboard/stats')) {
    return {
      activePolicies: 12,
      openClaims: 3,
      pendingApprovals: 4,
      premiumsMtd: 12500000,
      claimsReserveTotal: 2800000,
      renewalsDue30Days: 7,
      outstandingPremium: 1900000,
      riUtilisationPct: 42,
    };
  }

  if (pathname.endsWith('/dashboard/approval-queue')) {
    return { policies: 1, quotes: 1, endorsements: 0, claims: 1, receipts: 0, payments: 1 };
  }

  if (pathname.endsWith('/dashboard/loss-ratio')) {
    return [
      { month: 'Jan', premium: 1000000, claims: 250000, lossRatioPct: 25 },
      { month: 'Feb', premium: 1200000, claims: 300000, lossRatioPct: 25 },
    ];
  }

  if (pathname.endsWith('/dashboard/renewals-due')) {
    return [{ date: '2026-05-07', label: 'Today', count: 2 }];
  }

  if (pathname.endsWith('/dashboard/recent-activity')) {
    return [
      {
        id: 'act-1',
        entityType: 'POLICY',
        entityId: 'POL-001',
        action: 'CREATE',
        userName: 'Smoke Tester',
        timeAgo: 'just now',
        statusGroup: 'active',
      },
    ];
  }

  if (pathname.endsWith('/setup/company')) {
    return {
      id: 'company-1',
      companyName: 'Core Insurance',
      address: '1 Test Street',
      email: 'ops@example.com',
      phone: '+2348000000000',
      defaultCurrencyCode: 'NGN',
      createdAt: '2026-05-07T00:00:00Z',
      updatedAt: '2026-05-07T00:00:00Z',
    };
  }

  if (pathname.endsWith('/reports/definitions') || pathname.endsWith('/reports/pins')) {
    return [];
  }

  return emptyPage;
}
