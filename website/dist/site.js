// Screenshot switching stays local; the website never connects to the desktop app.
const views = {
  development: {
    source: "assets/app-development.jpg",
    title: "From a request to a reviewable change.",
    caption:
      "Give an agent a task, follow the conversation, and inspect changes in WorkBench. Open Tears of time to explore code and run tests.",
    guide: "guide-agents.html#agent-tasks",
    alt: "ByteQuay in dark mode with a completed development task beside its code changes and regression tests.",
  },
  review: {
    source: "assets/app-review.jpg",
    title: "Understand the change before you approve it.",
    caption:
      "Read the intention and commit explanations, inspect reproduced findings, and discuss them privately. Choose what belongs in your GitHub review.",
    guide: "guide-reviews.html#private-review",
    alt: "ByteQuay PR review with change intention, commit explanations, and a private finding.",
  },
  "cherry-pick": {
    source: "assets/app-cherry-pick.jpg",
    title: "The right commits. The right destination.",
    caption:
      "Check the branch route, selected commits, and predicted conflicts before starting. Follow the resolver and review the generated branch before delivery.",
    guide: "guide-agents.html#commits-and-backports",
    alt: "ByteQuay cherry-pick confirmation with five selected commits, destination branch, and conflict prediction.",
  },
  assistant: {
    source: "assets/app-assistant.jpg",
    title: "Your day and waiting tasks, one shortcut away.",
    caption:
      "Press ⌘J to ask about the work on screen and see what needs you. With task actions enabled, ask the Assistant to start work or pass along your reply.",
    guide: "guide-assistant.html",
    alt: "ByteQuay Assistant with on-screen context, a conversation, action receipts, and waiting task cards.",
  },
  epics: {
    source: "assets/app-epic-zoom.jpg",
    title: "A larger change, with every piece in view.",
    caption:
      "Group related work, connect issues and attempts, and follow progress. Review suggested work and decide what to add to the Epic.",
    guide: "guide-planning.html#epics",
    alt: "ByteQuay Epic in a zoom window over the workspace, showing progress, related issues, active tasks, and proposed work.",
  },
};
const buttons = document.querySelectorAll("[data-view]");
const screenshot = document.querySelector("#app-screenshot");
const link = document.querySelector("#screenshot-link");
const caption = document.querySelector("#screenshot-caption");
const title = document.querySelector("#screenshot-title");
const guide = document.querySelector("#screenshot-guide");
buttons.forEach((button) =>
  button.addEventListener("click", () => {
    const view = views[button.dataset.view];
    if (!view || !screenshot || !link || !caption || !title || !guide) return;
    screenshot.src = view.source;
    screenshot.alt = view.alt;
    link.href = view.source;
    link.setAttribute(
      "aria-label",
      `Open full-size ${button.textContent.trim()} screenshot`,
    );
    title.textContent = view.title;
    caption.textContent = view.caption;
    guide.href = view.guide;
    buttons.forEach((item) =>
      item.setAttribute("aria-pressed", String(item === button)),
    );
  }),
);
