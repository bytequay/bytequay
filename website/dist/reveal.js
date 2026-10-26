// Sections below the first screen rise in once. Without this script they simply show.
const observer = new IntersectionObserver(
  (entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        entry.target.classList.add("shown");
        observer.unobserve(entry.target);
      }
    }
  },
  { rootMargin: "0px 0px -10% 0px" },
);
for (const section of document.querySelectorAll(".band, .lab-showcase, .lab-close")) {
  if (section.getBoundingClientRect().top > innerHeight) {
    section.classList.add("reveal");
    observer.observe(section);
  }
}
