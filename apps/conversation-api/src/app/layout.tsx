import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'resend-service conversation API',
  description: 'Private API for topic-centered email conversations',
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
