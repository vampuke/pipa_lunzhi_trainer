---
version: alpha
name: Pipa Lunzhi Trainer
description: 东方民乐数字工具的质感——红木、丝弦金与宣纸米白，克制、温润、专业。
colors:
  primary: "#8C3A2B"
  primary-dark: "#5E271E"
  on-primary: "#FFFFFF"
  primary-container: "#F7DED4"
  on-primary-container: "#3A1710"
  accent: "#C8973F"
  accent-dark: "#9A6E26"
  on-accent: "#FFFFFF"
  accent-container: "#F6E7CC"
  surface: "#FFFBF8"
  surface-variant: "#F2E8DF"
  background: "#F6F1EA"
  outline: "#E3D6CA"
  text-primary: "#231A16"
  text-secondary: "#7A6A60"
  good: "#1C7D45"
  warn: "#8A6400"
  bad: "#C62F2F"
  chart-line: "#2255CC"
typography:
  display:
    fontFamily: sans-serif-medium
    fontSize: 30px
    fontWeight: 700
    lineHeight: 1.15
    letterSpacing: "-0.01em"
  h2:
    fontFamily: sans-serif-medium
    fontSize: 20px
    fontWeight: 700
    lineHeight: 1.25
  title:
    fontFamily: sans-serif-medium
    fontSize: 17px
    fontWeight: 600
    lineHeight: 1.3
  body:
    fontFamily: sans-serif
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: sans-serif-medium
    fontSize: 13px
    fontWeight: 600
    letterSpacing: "0.03em"
  caption:
    fontFamily: sans-serif
    fontSize: 12px
    fontWeight: 400
    textColor: "{colors.text-secondary}"
rounded:
  sm: 8px
  md: 16px
  lg: 24px
  pill: 999px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
components:
  hero:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.lg}"
    padding: 28px
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: 18px
  feature-icon:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.pill}"
    size: 48px
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.md}"
    padding: 14px
  button-primary-pressed:
    backgroundColor: "{colors.primary-dark}"
    textColor: "{colors.on-primary}"
  chip-accent:
    backgroundColor: "{colors.accent-container}"
    textColor: "{colors.accent-dark}"
    rounded: "{rounded.pill}"
    padding: 6px
  score-good:
    backgroundColor: "{colors.good}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.sm}"
  score-warn:
    backgroundColor: "{colors.warn}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.sm}"
  score-bad:
    backgroundColor: "{colors.bad}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.sm}"
---

## Overview

琵琶轮指训练是一款面向中国民乐练习者的分析工具。视觉基调取自琵琶本身：
**红木（primary）** 的温润深色作为品牌与头部底色，**丝弦金（accent）** 作为
点睛的高光与进度/强调，**宣纸米白（background/surface）** 做大面积留白。整体
克制、专业、有东方器物感，不花哨——数据与图表才是主角。

## Colors

- **Primary 红木 (#8C3A2B)：** 品牌主色，Hero 头部、主按钮、关键标题。
- **Primary Dark (#5E271E)：** 状态栏、按下态、渐变深端。
- **Accent 丝弦金 (#C8973F)：** 唯一的高光色——进度条、选中态、强调数字。
  克制使用，一屏不超过两三处，否则廉价。
- **Surface / Background：** 暖米白，`surface` 比 `background` 略亮，卡片浮于其上。
- **good / warn / bad：** 仅用于评分与均匀度等级徽标，不参与品牌表达。

对比度：`text-primary` on `surface` ≈ 13:1，`on-primary` on `primary` ≈ 6.4:1，
均过 WCAG AA。丝弦金只做大字/图形强调，不做小正文（金对白对比不足）。

## Typography

系统无衬线（Roboto / 思源黑）。层级靠字重与字号拉开：`display` 用于 Hero 标题，
`h2` 分区标题，`title` 卡片标题，`body` 正文，`label` 全大写小标签，`caption`
弱化辅助信息。中文不用斜体。

## Layout

8dp 栅格。页面左右边距 20dp，卡片间距 14dp，卡片内 18dp。Hero 头部圆底角
24dp，向下与内容衔接。

## Shapes

统一圆角：卡片 16dp、Hero/大面板 24dp、按钮 16dp、图标底托与徽标用 pill。

## Components

- **hero：** 红木实底（或红木→深红木 135° 渐变），承载应用名与副标题，底角圆。
- **card：** 米白表面、16dp 圆角、1dp 投影，承载每个功能入口与分析结果。
- **feature-icon：** 48dp pill 底托（primary-container），内嵌线性图标。
- **button-primary：** 红木实心，按下转深红木。
- **chip-accent / score-*：** 丝弦金信息胶囊；评分徽标按 good/warn/bad 取色。

## Do's and Don'ts

- Do 用大面积米白留白，让红木与丝弦金成为视觉焦点。
- Do 评分、等级这类"结论"才用彩色徽标；过程数据用中性灰。
- Don't 把丝弦金用在小正文或大色块——它只做点睛。
- Don't 在一个界面堆超过两种强调色。
