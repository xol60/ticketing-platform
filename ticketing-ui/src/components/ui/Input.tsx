import type { InputHTMLAttributes } from 'react';

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

export function Input({ label, error, id, className = '', ...rest }: Props) {
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={id} className="text-sm font-medium text-gray-700">{label}</label>
      )}
      <input
        id={id}
        {...rest}
        className={`block w-full rounded-lg border px-3 py-2 text-sm text-gray-900 placeholder-gray-400
          focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500
          disabled:bg-gray-50 disabled:text-gray-500
          ${error ? 'border-red-400 focus:ring-red-400' : 'border-gray-300'}
          ${className}`}
      />
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
}
