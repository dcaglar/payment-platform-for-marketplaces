import { Navigate, Route, Routes } from 'react-router-dom';
import { BalancesPage } from './features/balances/BalancesPage';
import { OwnBalancePage } from './features/balances/OwnBalancePage';
import { SellerBalancePage } from './features/balances/SellerBalancePage';
import { MerchantPickerPage } from './features/merchants/MerchantPickerPage';
import { Layout } from './features/session/Layout';
import { callerOf, useMe } from './features/session/session';
import { TransactionDetailPage } from './features/transactions/TransactionDetailPage';
import { TransactionListPage } from './features/transactions/TransactionListPage';

/**
 * The screens per kind of person. Merchant users and staff share the merchant screens: /merchants/me/… for a merchant
 * user, /merchants/{code}/… for staff, the same convention as the API. The API checks every call again.
 */
export function App() {
  const me = useMe();
  const caller = callerOf(me);

  if (caller === 'none') {
    return (
      <Layout>
        <p className="error">This login has no back office access.</p>
      </Layout>
    );
  }

  const merchantScreens = [
    <Route key="transactions" path="/merchants/:merchant/transactions" element={<TransactionListPage />} />,
    <Route key="transaction" path="/merchants/:merchant/transactions/:paymentId" element={<TransactionDetailPage />} />,
    <Route key="balances" path="/merchants/:merchant/balances" element={<BalancesPage />} />,
    <Route key="seller" path="/merchants/:merchant/sellers/:sellerId" element={<SellerBalancePage />} />,
  ];

  return (
    <Layout>
      <Routes>
        {caller === 'seller' && <Route path="/" element={<OwnBalancePage of="seller" />} />}
        {caller === 'merchant' && <Route path="/" element={<Navigate to="/merchants/me/transactions" replace />} />}
        {caller === 'staff' && <Route path="/" element={<MerchantPickerPage />} />}
        {caller !== 'seller' && merchantScreens}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  );
}
