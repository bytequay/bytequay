import { useEffect, useState } from 'react';
import type { DailyCardDto } from './types';
import { getCached, setCached } from './dataCache';

const CACHE_KEY = 'home:daily-card';

const TYPE_LABEL: Record<string, string> = {
  quote: 'Today’s quote',
  review_tip: 'Today’s review tip',
  open_source_tip: 'Today’s open-source tip',
  tiny_challenge: 'Today’s tiny challenge',
  joke: 'Today’s joke',
};

/**
 * Daily card on the home page (replaces the day-over-day stats strip).
 * Backend picks one card per calendar day from a curated pool (see
 * docs/mockups/v2/home/quote.md). The frontend only fetches and renders;
 * we cache the response in dataCache so a route remount paints instantly.
 */
function DailyCardSection() {
  const [card, setCard] = useState<DailyCardDto | null>(
    () => getCached<DailyCardDto>(CACHE_KEY) ?? null,
  );

  useEffect(() => {
    let cancelled = false;
    void window.bridge.getDailyCard()
      .then((c) => {
        if (cancelled) return;
        setCard(c);
        setCached(CACHE_KEY, c);
      })
      .catch(() => { /* non-fatal — leave the cached card visible */ });
    return () => { cancelled = true; };
  }, []);

  if (!card) {
    return (
      <div className="home-daily-card home-daily-card--loading">
        <span className="home-daily-card__skeleton" />
      </div>
    );
  }

  const isQuote = card.type === 'quote';
  // Tip / challenge / joke types still get an eyebrow so the card has
  // context (the text alone wouldn't read as advice). Quote cards rely
  // on the decorative quote glyphs and the "— Author" line to signal
  // their type, so the eyebrow is suppressed there per the design.
  const eyebrow = isQuote ? null : (TYPE_LABEL[card.type] ?? 'Today');
  return (
    <figure className={`home-daily-card home-daily-card--${card.type}`}>
      {eyebrow && <span className="home-daily-card__eyebrow">{eyebrow}</span>}
      <blockquote className="home-daily-card__text">
        {isQuote && <span className="home-daily-card__mark home-daily-card__mark--open" aria-hidden="true">“</span>}
        <span>{card.text}</span>
        {isQuote && <span className="home-daily-card__mark home-daily-card__mark--close" aria-hidden="true">”</span>}
      </blockquote>
      {card.author && (
        <figcaption className="home-daily-card__attribution">
          — <b>{card.author}</b>
          {card.role && <span className="home-daily-card__role">, {card.role}</span>}
        </figcaption>
      )}
    </figure>
  );
}

export default DailyCardSection;
