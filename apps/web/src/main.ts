import { checkApi } from "./apiStatus";
import "./style.css";

const dot = document.querySelector<HTMLSpanElement>("#api-dot");
const label = document.querySelector<HTMLSpanElement>("#api-status");

if (dot && label) {
  void checkApi(dot, label);
}
