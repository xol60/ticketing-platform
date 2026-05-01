import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import type { Role } from '../../types';

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState({ email: '', username: '', password: '', confirm: '', role: 'USER' as Role });
  const [errors, setErrors] = useState<Partial<typeof form>>({});
  const [apiError, setApiError] = useState('');
  const [loading, setLoading] = useState(false);

  const validate = () => {
    const e: Partial<typeof form> = {};
    if (!form.email) e.email = 'Email is required';
    if (!form.username || form.username.length < 3) e.username = 'Username must be at least 3 characters';
    if (!form.password || form.password.length < 8) e.password = 'Password must be at least 8 characters';
    if (form.password !== form.confirm) e.confirm = 'Passwords do not match';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handle = async (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setApiError('');
    setLoading(true);
    try {
      await register(form.email, form.username, form.password, form.role);
      navigate('/', { replace: true });
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setApiError(msg ?? 'Registration failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <span className="text-5xl">🎟️</span>
          <h1 className="text-2xl font-bold text-gray-900 mt-3">Create an account</h1>
          <p className="text-gray-500 text-sm mt-1">Join TicketHub today</p>
        </div>

        <form onSubmit={handle} className="bg-white rounded-2xl shadow-sm border border-gray-100 p-8 flex flex-col gap-4">
          {apiError && (
            <div className="bg-red-50 border border-red-200 text-red-700 text-sm rounded-lg px-4 py-2.5">
              {apiError}
            </div>
          )}

          <Input id="email" label="Email" type="email" required autoFocus
            value={form.email} error={errors.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
            placeholder="you@example.com" />

          <Input id="username" label="Username" required
            value={form.username} error={errors.username}
            onChange={(e) => setForm({ ...form, username: e.target.value })}
            placeholder="johndoe" />

          <Input id="password" label="Password" type="password" required
            value={form.password} error={errors.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            placeholder="Min. 8 characters" />

          <Input id="confirm" label="Confirm password" type="password" required
            value={form.confirm} error={errors.confirm}
            onChange={(e) => setForm({ ...form, confirm: e.target.value })}
            placeholder="Repeat password" />

          {/* Role selector */}
          <div className="flex flex-col gap-1">
            <label className="text-sm font-medium text-gray-700">I want to…</label>
            <div className="grid grid-cols-2 gap-2">
              {([['USER', '🎫 Buy tickets'], ['EVENT_OWNER', '🎭 Create events']] as [Role, string][]).map(([val, label]) => (
                <button
                  key={val} type="button"
                  onClick={() => setForm({ ...form, role: val })}
                  className={`px-3 py-2.5 rounded-lg border text-sm font-medium transition-colors
                    ${form.role === val
                      ? 'border-blue-600 bg-blue-50 text-blue-700'
                      : 'border-gray-200 text-gray-600 hover:bg-gray-50'}`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          <Button type="submit" loading={loading} size="lg" className="mt-2 w-full">
            Create account
          </Button>
        </form>

        <p className="text-center text-sm text-gray-500 mt-4">
          Already have an account?{' '}
          <Link to="/login" className="text-blue-600 font-medium hover:underline">Sign in</Link>
        </p>
      </div>
    </div>
  );
}
