import { defineConfig } from 'astro/config'
import starlight from '@astrojs/starlight'

// Marketing and documentation are not part of the product: no container, no runtime, built to HTML
// and deployed separately (SDD.md 8). The app owns /app and /a/{token} on the same apex domain.
export default defineConfig({
  site: 'https://mantel.example',
  integrations: [
    starlight({
      title: 'Mantel',
      description: 'Photo and video albums shared as a link. No account, no app, no tracking.',
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/alternayte/mantel' }],
      sidebar: [
        {
          label: 'Start',
          items: [
            { label: 'What it is', link: '/' },
            { label: 'Getting started', link: '/getting-started/' },
          ],
        },
        {
          label: 'Running it',
          items: [
            { label: 'Self-hosting', link: '/self-hosting/' },
            { label: 'Configuration', link: '/configuration/' },
          ],
        },
        {
          label: 'Building on it',
          items: [
            { label: 'API', link: '/api/' },
            { label: 'Agents', link: '/agents/' },
          ],
        },
      ],
      customCss: ['./src/styles/tokens.css'],
    }),
  ],
})
