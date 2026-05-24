import { Button as ButtonPrimitive } from "@base-ui/react/button"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const buttonVariants = cva(
  // Brutalista: borde negro grueso, mayúsculas apretadas, sombra dura desplazada
  // y efecto de "pulsado físico" (se hunde sobre su propia sombra al accionar).
  "group/button inline-flex shrink-0 items-center justify-center gap-2 border-2 border-foreground font-semibold uppercase tracking-wide whitespace-nowrap transition-[transform,box-shadow,background-color] duration-100 outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground brutal-shadow hover:-translate-x-px hover:-translate-y-px hover:shadow-[5px_5px_0_0_var(--color-foreground)] active:translate-x-[2px] active:translate-y-[2px] active:shadow-[1px_1px_0_0_var(--color-foreground)]",
        outline:
          "bg-card text-foreground brutal-shadow hover:-translate-x-px hover:-translate-y-px hover:shadow-[5px_5px_0_0_var(--color-foreground)] active:translate-x-[2px] active:translate-y-[2px] active:shadow-[1px_1px_0_0_var(--color-foreground)]",
        secondary:
          "bg-secondary text-secondary-foreground brutal-shadow hover:-translate-x-px hover:-translate-y-px hover:shadow-[5px_5px_0_0_var(--color-foreground)] active:translate-x-[2px] active:translate-y-[2px] active:shadow-[1px_1px_0_0_var(--color-foreground)]",
        ghost:
          "border-transparent hover:bg-muted hover:text-foreground active:translate-y-px",
        destructive:
          "bg-destructive text-white brutal-shadow hover:-translate-x-px hover:-translate-y-px hover:shadow-[5px_5px_0_0_var(--color-foreground)] active:translate-x-[2px] active:translate-y-[2px] active:shadow-[1px_1px_0_0_var(--color-foreground)]",
        link: "border-transparent text-primary underline-offset-4 hover:underline",
      },
      size: {
        default: "h-11 px-4 text-sm",
        xs: "h-8 px-2.5 text-xs [&_svg:not([class*='size-'])]:size-3",
        sm: "h-9 px-3 text-xs [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-14 px-6 text-base",
        icon: "size-11",
        "icon-xs": "size-8 [&_svg:not([class*='size-'])]:size-3.5",
        "icon-sm": "size-9",
        "icon-lg": "size-14",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  return (
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
