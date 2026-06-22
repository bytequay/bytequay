import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';

import styles from './index.module.css';

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={clsx('hero hero--primary', styles.heroBanner)}>
      <div className="container">
        <h1 className="hero__title">{siteConfig.title}</h1>
        <p className="hero__subtitle">{siteConfig.tagline}</p>
        <div className={styles.buttons}>
          <Link
            className="button button--secondary button--lg"
            to="/docs/usage/getting-started">
            Get started
          </Link>
          <Link
            className="button button--outline button--secondary button--lg"
            to="/docs/">
            Read the docs
          </Link>
        </div>
      </div>
    </header>
  );
}

const cards = [
  {
    title: 'Usage',
    body: 'Task-oriented guides for getting things done in ByteQuay.',
    to: '/docs/usage/getting-started',
  },
  {
    title: 'Release notes',
    body: 'What changed in each version, newest first.',
    to: '/releases',
  },
];

function HomepageCards() {
  return (
    <section className={styles.cards}>
      <div className="container">
        <div className="row">
          {cards.map((card) => (
            <div className="col col--6" key={card.title}>
              <Link className={styles.card} to={card.to}>
                <h3>{card.title}</h3>
                <p>{card.body}</p>
              </Link>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

export default function Home(): ReactNode {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={siteConfig.title}
      description="ByteQuay documentation — usage guides and release notes.">
      <HomepageHeader />
      <main>
        <HomepageCards />
      </main>
    </Layout>
  );
}
