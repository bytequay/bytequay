// Screenshot switching stays local; the website never connects to the desktop app.
const views = {
  development: {
    source: "assets/app-development.jpg",
    title: "From a request to a reviewable change.",
    caption:
      "Give an agent a task. Follow the conversation, inspect the changes, and check the tests.",
    guide: "guide-agents.html#agent-tasks",
    alt: "ByteQuay in dark mode with a completed development task beside its code changes and regression tests.",
  },
  review: {
    source: "assets/app-review.jpg",
    title: "Understand the change before you approve it.",
    caption:
      "Understand the intent, inspect findings, and discuss them privately before publishing your review.",
    guide: "guide-reviews.html#private-review",
    alt: "ByteQuay PR review with change intention, commit explanations, and a private finding.",
  },
  "cherry-pick": {
    source: "assets/app-cherry-pick.jpg",
    title: "The right commits. The right destination.",
    caption:
      "Check the destination, commits, and predicted conflicts. Review the result before delivery.",
    guide: "guide-agents.html#commits-and-backports",
    alt: "ByteQuay cherry-pick confirmation with five selected commits, destination branch, and conflict prediction.",
  },
  assistant: {
    source: "assets/app-assistant.jpg",
    title: "Your day and waiting tasks, one shortcut away.",
    caption:
      "Press ⌘J to see what needs you. With task actions enabled, ask it to start work or pass along a reply.",
    guide: "guide-assistant.html",
    alt: "ByteQuay Assistant with on-screen context, a conversation, action receipts, and waiting task cards.",
  },
  epics: {
    source: "assets/app-epic-zoom.jpg",
    title: "A larger change, with every piece in view.",
    caption:
      "Connect related work, follow progress, and choose what to take on next.",
    guide: "guide-planning.html#epics",
    alt: "ByteQuay Epic in a zoom window over the workspace, showing progress, related issues, active tasks, and proposed work.",
  },
};

function initializeCarousel() {
  const carousel = document.querySelector("#the-app");
  if (!carousel) return;

  const buttons = [...carousel.querySelectorAll("[data-view]")];
  const screenshot = carousel.querySelector("#app-screenshot");
  const link = carousel.querySelector("#screenshot-link");
  const caption = carousel.querySelector("#screenshot-caption");
  const title = carousel.querySelector("#screenshot-title");
  const guide = carousel.querySelector("#screenshot-guide");
  const description = carousel.querySelector(".showcase-description");
  const count = carousel.querySelector(".carousel-count");
  const toggle = carousel.querySelector(".carousel-toggle");
  const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let current = 0;
  let paused = motion.matches;
  let hovering = false;
  let visible = false;
  let timer;

  // Warm the five small images so automatic changes do not wait on a download.
  Object.values(views).forEach((view) => { new Image().src = view.source; });
  carousel.querySelectorAll("[hidden]").forEach((control) => { control.hidden = false; });

  function updatePlayback() {
    window.clearTimeout(timer);
    const rotating = !paused && !hovering && visible && !document.hidden;
    carousel.dataset.rotating = String(rotating);
    carousel.dataset.paused = String(paused);
    description.setAttribute("aria-live", rotating ? "off" : "polite");
    toggle.setAttribute("aria-label", paused ? "Play slideshow" : "Pause slideshow");
    toggle.querySelector("span").textContent = paused ? "Play" : "Pause";
    if (rotating) timer = window.setTimeout(() => show(current + 1), 2000);
  }

  function show(index) {
    current = (index + buttons.length) % buttons.length;
    const button = buttons[current];
    const view = views[button.dataset.view];
    screenshot.src = view.source;
    screenshot.alt = view.alt;
    link.href = view.source;
    link.setAttribute("aria-label", `Open full-size ${button.textContent.trim()} screenshot`);
    title.textContent = view.title;
    caption.textContent = view.caption;
    guide.href = view.guide;
    count.firstChild.textContent = `${String(current + 1).padStart(2, "0")} `;
    count.setAttribute("aria-label", `Screenshot ${current + 1} of ${buttons.length}`);
    buttons.forEach((item) => item.setAttribute("aria-pressed", String(item === button)));
    if (!motion.matches) screenshot.animate([{ opacity: 0.65 }, { opacity: 1 }], { duration: 240 });
    updatePlayback();
  }

  buttons.forEach((button, index) => button.addEventListener("click", () => show(index)));
  carousel.querySelector(".carousel-previous").addEventListener("click", () => show(current - 1));
  carousel.querySelector(".carousel-next").addEventListener("click", () => show(current + 1));
  toggle.addEventListener("click", () => {
    paused = !paused;
    if (!paused) hovering = false;
    updatePlayback();
  });
  carousel.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    paused = true;
    const index = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1
      : current + (event.key === "ArrowRight" ? 1 : -1);
    show(index);
    if (event.target.matches("[data-view]")) buttons[current].focus();
  });
  carousel.addEventListener("focusin", (event) => {
    if (event.target.matches(":focus-visible")) {
      paused = true;
      updatePlayback();
    }
  });
  carousel.addEventListener("pointerenter", (event) => {
    if (event.pointerType === "mouse") {
      hovering = true;
      updatePlayback();
    }
  });
  carousel.addEventListener("pointerleave", () => {
    hovering = false;
    updatePlayback();
  });
  document.addEventListener("visibilitychange", updatePlayback);
  motion.addEventListener("change", () => {
    paused = motion.matches;
    updatePlayback();
  });
  new IntersectionObserver(([entry]) => {
    visible = entry.isIntersecting;
    updatePlayback();
  }, { threshold: 0 }).observe(carousel);
  updatePlayback();
}
initializeCarousel();
