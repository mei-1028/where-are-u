<script setup lang="ts">
withDefaults(
  defineProps<{
    preserve?: 'meet' | 'none'
    hideLabels?: boolean
    viewBox?: string
  }>(),
  { preserve: 'meet', hideLabels: false, viewBox: '0 0 1200 820' },
)
</script>

<template>
  <svg
    class="schematic"
    :class="{ 'schematic--hide-labels': hideLabels }"
    :viewBox="viewBox"
    :preserveAspectRatio="preserve === 'none' ? 'none' : 'xMidYMid meet'"
    aria-hidden="true"
    focusable="false"
  >
    <defs>
      <pattern id="map-dots" width="26" height="26" patternUnits="userSpaceOnUse">
        <circle cx="1.2" cy="1.2" r="1.1" fill="#E6E8ED" />
      </pattern>
      <pattern id="park-hatch" width="14" height="14" patternUnits="userSpaceOnUse">
        <path d="M0 14 14 0" stroke="#D6E9DA" stroke-width="1" />
      </pattern>
    </defs>

    <!-- Canvas -->
    <rect width="1200" height="820" fill="#F6F7F9" />
    <rect width="1200" height="820" fill="url(#map-dots)" opacity="0.85" />

    <!-- Canal -->
    <path
      d="M1176 -30 C1145 170 1182 350 1152 540 C1132 672 1162 764 1136 850"
      fill="none"
      stroke="#D9EDEF"
      stroke-width="38"
    />
    <path
      d="M1176 -30 C1145 170 1182 350 1152 540 C1132 672 1162 764 1136 850"
      fill="none"
      stroke="#EAF6F7"
      stroke-width="22"
    />

    <!-- West Lake -->
    <path
      d="M14 638 C118 580 236 568 322 604 C386 632 432 686 508 706 C574 724 604 770 574 810 C510 826 120 830 54 802 C4 780 -14 686 14 638 Z"
      fill="#DDEEF1"
      stroke="#CDE4E7"
      stroke-width="2"
    />
    <g fill="none" stroke="#C8E0E3" stroke-width="1.4" opacity="0.75">
      <path d="M70 700 C 150 668 210 676 270 704" />
      <path d="M116 758 C 196 728 286 738 360 766" />
      <path d="M420 744 C 472 734 518 748 546 770" />
    </g>

    <!-- Huanglong park -->
    <path
      d="M284 524 C 356 500 426 524 468 580 C 502 626 482 682 420 704 C 358 726 298 702 272 652 C 250 610 252 548 284 524 Z"
      fill="#E6F2E8"
      stroke="#D7E8DA"
      stroke-width="2"
    />
    <path
      d="M284 524 C 356 500 426 524 468 580 C 502 626 482 682 420 704 C 358 726 298 702 272 652 C 250 610 252 548 284 524 Z"
      fill="url(#park-hatch)"
      opacity="0.55"
    />
    <g fill="none" stroke="#FFFFFF" stroke-width="2.4" opacity="0.85">
      <path d="M300 548 C 348 574 376 612 392 660" />
      <path d="M340 530 C 386 568 420 608 438 654" />
      <path d="M304 620 C 348 606 396 612 432 636" />
    </g>
    <g fill="#CDE5D2">
      <circle cx="310" cy="588" r="5" />
      <circle cx="330" cy="646" r="4" />
      <circle cx="376" cy="540" r="4.5" />
      <circle cx="438" cy="612" r="5" />
      <circle cx="410" cy="676" r="4" />
      <circle cx="356" cy="676" r="3.6" />
      <circle cx="296" cy="554" r="3.4" />
    </g>
    <ellipse
      cx="378"
      cy="624"
      rx="26"
      ry="15"
      fill="#D8EBEE"
      stroke="#C8E0E3"
      stroke-width="1.2"
    />

    <!-- City blocks -->
    <g fill="#EDEFF3">
      <rect x="60" y="40" width="150" height="58" rx="14" />
      <rect x="230" y="40" width="90" height="58" rx="14" />
      <rect x="520" y="40" width="140" height="58" rx="14" />
      <rect x="690" y="40" width="150" height="58" rx="14" />
      <rect x="870" y="40" width="140" height="58" rx="14" />
      <rect x="1040" y="40" width="92" height="58" rx="14" />
      <rect x="60" y="132" width="150" height="82" rx="14" />
      <rect x="230" y="132" width="86" height="82" rx="14" />
      <rect x="350" y="132" width="150" height="82" rx="14" />
      <rect x="530" y="132" width="122" height="82" rx="14" />
      <rect x="680" y="132" width="150" height="82" rx="14" />
      <rect x="860" y="132" width="140" height="82" rx="14" />
      <rect x="1030" y="132" width="102" height="82" rx="14" />
      <rect x="60" y="258" width="160" height="80" rx="14" />
      <rect x="245" y="258" width="66" height="80" rx="14" />
      <rect x="350" y="258" width="150" height="80" rx="14" />
      <rect x="530" y="258" width="122" height="80" rx="14" />
      <rect x="680" y="258" width="150" height="80" rx="14" />
      <rect x="860" y="258" width="140" height="80" rx="14" />
      <rect x="1030" y="258" width="102" height="80" rx="14" />
      <rect x="60" y="382" width="160" height="80" rx="14" />
      <rect x="245" y="382" width="66" height="80" rx="14" />
      <rect x="530" y="382" width="122" height="80" rx="14" />
      <rect x="680" y="382" width="150" height="80" rx="14" />
      <rect x="860" y="382" width="140" height="80" rx="14" />
      <rect x="1030" y="382" width="102" height="80" rx="14" />
      <rect x="530" y="504" width="122" height="82" rx="14" />
      <rect x="680" y="504" width="150" height="82" rx="14" />
      <rect x="860" y="504" width="140" height="82" rx="14" />
      <rect x="1030" y="504" width="102" height="82" rx="14" />
      <rect x="530" y="628" width="122" height="78" rx="14" />
      <rect x="680" y="628" width="150" height="78" rx="14" />
      <rect x="860" y="628" width="140" height="78" rx="14" />
      <rect x="1030" y="628" width="102" height="78" rx="14" />
      <rect x="700" y="748" width="130" height="48" rx="14" />
      <rect x="860" y="748" width="140" height="48" rx="14" />
      <rect x="1030" y="748" width="102" height="48" rx="14" />
    </g>

    <!-- Two pastel accent blocks, echoing the Miro palette -->
    <g>
      <rect x="350" y="40" width="140" height="58" rx="14" fill="#FFEDED" />
      <rect x="350" y="382" width="150" height="80" rx="14" fill="#FFF6EC" />
    </g>

    <!-- Building footprints -->
    <g fill="#E2E4EA">
      <rect x="78" y="150" width="42" height="22" rx="5" />
      <rect x="130" y="156" width="58" height="18" rx="4" />
      <rect x="78" y="184" width="66" height="16" rx="4" />
      <rect x="368" y="282" width="56" height="26" rx="5" />
      <rect x="434" y="292" width="44" height="24" rx="5" />
      <rect x="548" y="150" width="48" height="30" rx="5" />
      <rect x="606" y="158" width="30" height="22" rx="4" />
      <rect x="700" y="280" width="62" height="28" rx="5" />
      <rect x="776" y="290" width="34" height="24" rx="4" />
      <rect x="884" y="150" width="46" height="26" rx="5" />
      <rect x="940" y="160" width="42" height="22" rx="4" />
      <rect x="884" y="402" width="58" height="26" rx="5" />
      <rect x="952" y="412" width="28" height="22" rx="4" />
      <rect x="702" y="526" width="52" height="26" rx="5" />
      <rect x="766" y="534" width="46" height="22" rx="4" />
      <rect x="884" y="650" width="54" height="26" rx="5" />
      <rect x="944" y="658" width="32" height="20" rx="4" />
      <rect x="716" y="762" width="42" height="20" rx="4" />
      <rect x="878" y="762" width="54" height="20" rx="4" />
      <rect x="366" y="402" width="46" height="22" rx="4" />
      <rect x="422" y="410" width="34" height="20" rx="4" />
      <rect x="1048" y="280" width="56" height="26" rx="5" />
    </g>
    <g fill="#DADDE4" opacity="0.75">
      <rect x="548" y="268" width="22" height="22" rx="4" />
      <rect x="584" y="274" width="42" height="18" rx="4" />
      <rect x="78" y="270" width="26" height="22" rx="4" />
      <rect x="116" y="276" width="52" height="18" rx="4" />
    </g>

    <!-- Plaza & stadium landmarks -->
    <g>
      <circle cx="842" cy="470" r="25" fill="#F8F8FA" stroke="#E2E4EA" stroke-width="2" />
      <path d="M826 470h32M842 454v32" stroke="#E4E6EC" stroke-width="2" />
      <ellipse
        cx="330"
        cy="602"
        rx="30"
        ry="19"
        fill="#F9FAFB"
        stroke="#D9DCE4"
        stroke-width="2"
      />
      <ellipse
        cx="330"
        cy="602"
        rx="15"
        ry="9"
        fill="none"
        stroke="#E2E5EB"
        stroke-width="1.6"
      />
    </g>

    <!-- Roads: casing + asphalt -->
    <g fill="none" stroke-linecap="round">
      <g stroke="#DFE1E7">
        <path d="M-20 112 C 200 105 380 118 620 110 C 820 104 1002 112 1220 104" stroke-width="24" />
        <path d="M-20 238 C 220 231 420 243 640 236 C 840 230 1020 240 1220 232" stroke-width="24" />
        <path d="M-20 362 C 220 354 420 368 640 360 C 840 352 1020 364 1220 356" stroke-width="34" />
        <path d="M-20 482 C 220 474 420 488 640 480 C 840 472 1020 486 1220 476" stroke-width="26" />
        <path d="M516 614 C 660 606 840 620 1220 608" stroke-width="24" />
        <path d="M592 732 C 720 724 900 738 1220 728" stroke-width="22" />
        <path d="M130 20 C 126 190 134 380 128 556" stroke-width="22" />
        <path d="M326 20 C 322 170 330 340 324 520" stroke-width="22" />
        <path d="M516 20 C 512 220 522 480 514 806" stroke-width="24" />
        <path d="M696 20 C 692 240 702 520 694 806" stroke-width="26" />
        <path d="M876 20 C 872 240 882 520 874 806" stroke-width="24" />
        <path d="M1050 20 C 1046 240 1056 520 1048 806" stroke-width="22" />
      </g>
      <g stroke="#FFFFFF">
        <path d="M-20 112 C 200 105 380 118 620 110 C 820 104 1002 112 1220 104" stroke-width="18" />
        <path d="M-20 238 C 220 231 420 243 640 236 C 840 230 1020 240 1220 232" stroke-width="18" />
        <path d="M-20 362 C 220 354 420 368 640 360 C 840 352 1020 364 1220 356" stroke-width="27" />
        <path d="M-20 482 C 220 474 420 488 640 480 C 840 472 1020 486 1220 476" stroke-width="19" />
        <path d="M516 614 C 660 606 840 620 1220 608" stroke-width="18" />
        <path d="M592 732 C 720 724 900 738 1220 728" stroke-width="16" />
        <path d="M130 20 C 126 190 134 380 128 556" stroke-width="16" />
        <path d="M326 20 C 322 170 330 340 324 520" stroke-width="16" />
        <path d="M516 20 C 512 220 522 480 514 806" stroke-width="18" />
        <path d="M696 20 C 692 240 702 520 694 806" stroke-width="19" />
        <path d="M876 20 C 872 240 882 520 874 806" stroke-width="18" />
        <path d="M1050 20 C 1046 240 1056 520 1048 806" stroke-width="16" />
      </g>
      <!-- subtle centerline on the main road -->
      <path
        d="M-20 362 C 220 354 420 368 640 360 C 840 352 1020 364 1220 356"
        stroke="#ECEEF2"
        stroke-width="1.4"
        stroke-dasharray="12 14"
      />
    </g>

    <!-- Intersection nodes -->
    <g fill="#FFFFFF" stroke="#E3E5EB" stroke-width="1.2">
      <circle cx="516" cy="362" r="5.5" />
      <circle cx="696" cy="362" r="5.5" />
      <circle cx="876" cy="362" r="5.5" />
      <circle cx="516" cy="482" r="5" />
      <circle cx="696" cy="482" r="5" />
      <circle cx="876" cy="482" r="5" />
      <circle cx="696" cy="236" r="5" />
      <circle cx="876" cy="236" r="5" />
    </g>

    <!-- Labels -->
    <g class="road-label">
      <text x="742" y="353">文三路 · WENSHAN RD</text>
      <text x="742" y="103">余杭塘路</text>
      <text x="742" y="230">文一西路</text>
      <text x="768" y="473">天目山路</text>
      <text x="940" y="604">曙光路</text>
    </g>
    <g class="road-label" transform="rotate(-90 337 500)">
      <text x="337" y="500">学院路</text>
    </g>
    <g class="road-label" transform="rotate(-90 527 590)">
      <text x="527" y="590">教工路</text>
    </g>
    <g class="road-label" transform="rotate(-90 707 300)">
      <text x="707" y="300">莫干山路</text>
    </g>
    <g class="road-label" transform="rotate(-90 887 250)">
      <text x="887" y="250">中河高架</text>
    </g>
    <g class="road-label" transform="rotate(-90 1188 420)">
      <text x="1188" y="420">京杭大运河</text>
    </g>

    <g class="area-label">
      <text x="92" y="248">翠 苑</text>
      <text x="356" y="670">黄龙洞公园</text>
      <text x="792" y="510">武林广场</text>
      <text x="246" y="640">黄龙体育中心</text>
      <text x="782" y="78">拱墅区</text>
      <text x="148" y="472">西湖区</text>
    </g>
    <text x="286" y="774" class="water-label">西 湖</text>

    <!-- Compass rose -->
    <g transform="translate(1128 748)" opacity="0.7">
      <circle r="18" fill="#FFFFFF" stroke="#E3E5EB" />
      <path d="M0 -10 4 4 0 1 -4 4Z" fill="#8B8F9C" />
      <text x="0" y="-21" text-anchor="middle" class="compass-label">N</text>
    </g>
  </svg>
</template>

<style scoped>
.schematic {
  width: 100%;
  height: 100%;
  display: block;
}

.road-label text {
  font-size: 10px;
  letter-spacing: 0.11em;
  fill: #a2a6b1;
  font-weight: 500;
}

.area-label text {
  font-size: 10.5px;
  letter-spacing: 0.12em;
  fill: #b0b3bd;
}

.water-label {
  font-size: 30px;
  letter-spacing: 0.42em;
  fill: #9ec3c7;
  font-weight: 500;
}

.compass-label {
  font-size: 8.5px;
  letter-spacing: 0.12em;
  fill: #9296a2;
  font-weight: 600;
}

.schematic--hide-labels .road-label,
.schematic--hide-labels .area-label,
.schematic--hide-labels .water-label,
.schematic--hide-labels .compass-label {
  display: none;
}
</style>
