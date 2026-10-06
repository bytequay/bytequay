// Screenshot switching stays local; the website never connects to the desktop app.
const views = {
  review: {
    source: "assets/app-review.jpg",
    alt: "ByteQuay pull-request review showing a change summary, commit explanations, and private findings.",
    caption:
      "Understand the intent, inspect each commit, and discuss findings privately.",
    label: "Open full-size pull-request review screenshot",
  },
  agents: {
    source: "assets/app-agents.jpg",
    alt: "ByteQuay agent task with a conversation, tool activity, and follow-up composer.",
    caption:
      "Follow a local agent task from the first request to the next decision.",
    label: "Open full-size agent task screenshot",
  },
};
const buttons = document.querySelectorAll("[data-view]");
const screenshot = document.querySelector("#app-screenshot");
const link = document.querySelector("#screenshot-link");
const caption = document.querySelector("#screenshot-caption");
buttons.forEach((button) =>
  button.addEventListener("click", () => {
    const view = views[button.dataset.view];
    if (!view || !screenshot || !link || !caption) return;
    screenshot.src = view.source;
    screenshot.alt = view.alt;
    link.href = view.source;
    link.setAttribute("aria-label", view.label);
    caption.textContent = view.caption;
    buttons.forEach((item) =>
      item.setAttribute("aria-pressed", String(item === button)),
    );
  }),
);
