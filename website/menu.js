const header = document.querySelector(".site-header");
const toggle = header?.querySelector(".menu-toggle");
const nav = header?.querySelector("#primary-nav");

if (header && toggle && nav) {
  const closeMenu = () => {
    header.classList.remove("nav-open");
    toggle.setAttribute("aria-expanded", "false");
  };

  toggle.addEventListener("click", () => {
    const expanded = toggle.getAttribute("aria-expanded") === "true";
    header.classList.toggle("nav-open", !expanded);
    toggle.setAttribute("aria-expanded", String(!expanded));
  });

  nav.querySelectorAll("a").forEach((link) => link.addEventListener("click", closeMenu));
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeMenu();
  });
  window.addEventListener("resize", () => {
    if (window.matchMedia("(min-width: 801px)").matches) closeMenu();
  });
}
