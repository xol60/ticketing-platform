import { Routes, Route, Navigate } from 'react-router-dom';
import { Layout } from '../components/layout/Layout';
import { PrivateRoute, RoleRoute } from './guards';

// Auth
import { LoginPage }    from '../pages/auth/LoginPage';
import { RegisterPage } from '../pages/auth/RegisterPage';
import { ProfilePage }  from '../pages/auth/ProfilePage';

// Events
import { EventListPage }   from '../pages/events/EventListPage';
import { EventDetailPage } from '../pages/events/EventDetailPage';

// Orders / Tickets
import { TicketDetailPage }   from '../pages/orders/TicketDetailPage';
import { OrderTrackerPage }   from '../pages/orders/OrderTrackerPage';
import { OrderListPage }      from '../pages/orders/OrderListPage';
import { OrderDetailPage }    from '../pages/orders/OrderDetailPage';

// Secondary market
import { SecondaryMarketPage } from '../pages/secondary/SecondaryMarketPage';

// Creator
import { CreatorDashboard }    from '../pages/creator/CreatorDashboard';
import { TicketManagerPage }   from '../pages/creator/TicketManagerPage';
import { PricingManagerPage }  from '../pages/creator/PricingManagerPage';

export function AppRouter() {
  return (
    <Routes>
      {/* Public auth routes (no layout) */}
      <Route path="/login"    element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      {/* Layout wrapper — all routes below share Navbar */}
      <Route element={<Layout />}>
        {/* Public */}
        <Route path="/"                element={<EventListPage />} />
        <Route path="/events/:eventId" element={<EventDetailPage />} />
        <Route path="/secondary"       element={<SecondaryMarketPage />} />

        {/* Authenticated only */}
        <Route element={<PrivateRoute />}>
          <Route path="/tickets/:ticketId"        element={<TicketDetailPage />} />
          <Route path="/orders"                   element={<OrderListPage />} />
          <Route path="/orders/:orderId"          element={<OrderDetailPage />} />
          <Route path="/orders/:orderId/track"    element={<OrderTrackerPage />} />
          <Route path="/profile"                  element={<ProfilePage />} />
        </Route>

        {/* EVENT_OWNER only */}
        <Route element={<RoleRoute role="EVENT_OWNER" />}>
          <Route path="/creator"                                    element={<CreatorDashboard />} />
          <Route path="/creator/events/:eventId/tickets"            element={<TicketManagerPage />} />
          <Route path="/creator/events/:eventId/pricing"            element={<PricingManagerPage />} />
        </Route>

        {/* Fallback */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
