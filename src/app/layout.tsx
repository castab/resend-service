import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'resend-service',
  description: 'Resend webhooks and topic-centered email conversations',
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
