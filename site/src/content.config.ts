import { docsSchema } from '@astrojs/starlight/schema'
import { defineCollection } from 'astro:content'
import { glob } from 'astro/loaders'

// The base is named rather than left to the default: the default resolved to nothing here, and a
// docs site that silently builds zero pages is worse than one that fails.
export const collections = {
  docs: defineCollection({
    loader: glob({ pattern: '**/*.{md,mdx}', base: './src/content/docs' }),
    schema: docsSchema(),
  }),
}
