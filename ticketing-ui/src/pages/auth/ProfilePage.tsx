import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Card, CardHeader, CardBody } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import type { Role } from '../../types';

const roleBadge: Record<Role, { label: string; variant: 'blue' | 'purple' | 'gray' }> = {
  USER:        { label: 'Buyer',         variant: 'blue'   },
  EVENT_OWNER: { label: 'Event Creator', variant: 'purple' },
  ADMIN:       { label: 'Admin',         variant: 'gray'   },
  SUPPORT:     { label: 'Support',       variant: 'gray'   },
};

export function ProfilePage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [logoutAll, setLogoutAll] = useState(false);

  if (!user) return null;

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const rb = roleBadge[user.role] ?? { label: user.role, variant: 'gray' as const };

  return (
    <div className="max-w-lg mx-auto flex flex-col gap-6">
      <h1 className="text-2xl font-bold text-gray-900">My Profile</h1>

      <Card>
        <CardHeader title="Account details" />
        <CardBody className="flex flex-col gap-4">
          {/* Avatar */}
          <div className="flex items-center gap-4">
            <div className="w-16 h-16 bg-blue-600 rounded-full flex items-center justify-center text-white text-2xl font-bold uppercase">
              {user.username.charAt(0)}
            </div>
            <div>
              <p className="text-base font-semibold text-gray-900">{user.username}</p>
              <p className="text-sm text-gray-500">{user.email}</p>
              <div className="mt-1">
                <Badge label={rb.label} variant={rb.variant} />
              </div>
            </div>
          </div>

          <hr className="border-gray-100" />

          {/* Info rows */}
          <dl className="grid grid-cols-2 gap-y-3 text-sm">
            <dt className="text-gray-500">User ID</dt>
            <dd className="text-gray-800 font-mono text-xs truncate">{user.id}</dd>

            <dt className="text-gray-500">Email verified</dt>
            <dd>
              {user.emailVerified
                ? <Badge label="Verified" variant="green" />
                : <Badge label="Not verified" variant="yellow" />}
            </dd>

            <dt className="text-gray-500">Account status</dt>
            <dd>
              {user.enabled
                ? <Badge label="Active" variant="green" />
                : <Badge label="Disabled" variant="red" />}
            </dd>

            <dt className="text-gray-500">Member since</dt>
            <dd className="text-gray-800">{new Date(user.createdAt).toLocaleDateString()}</dd>
          </dl>
        </CardBody>
      </Card>

      <Card>
        <CardHeader title="Session" />
        <CardBody className="flex flex-col gap-3">
          <div className="flex items-center gap-3">
            <input id="logoutAll" type="checkbox" checked={logoutAll} onChange={(e) => setLogoutAll(e.target.checked)}
              className="rounded border-gray-300 text-blue-600" />
            <label htmlFor="logoutAll" className="text-sm text-gray-600">Sign out from all devices</label>
          </div>
          <Button variant="danger" onClick={handleLogout}>
            {logoutAll ? 'Sign out from all devices' : 'Sign out'}
          </Button>
        </CardBody>
      </Card>
    </div>
  );
}
