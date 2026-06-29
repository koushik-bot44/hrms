/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Resolve and transpile the shared workspace package from source.
  transpilePackages: ['@cdpp/shared'],
};

module.exports = nextConfig;
