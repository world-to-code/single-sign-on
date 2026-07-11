import { common } from "./common";
import { nav } from "./nav";
import { auth } from "./auth";
import { console } from "./console";
import { errors } from "./errors";
import { states } from "./states";
import { validation } from "./validation";

export const ko = { common, nav, auth, console, errors, states, validation } as const;
